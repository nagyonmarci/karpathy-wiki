package com.danvega.wiki.ingest;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.ai.chat.client.ChatClient;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

class ChatGptConversationConverter implements FileConverter {

    private static final int MAX_CHARS = 12_000;
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Override
    public boolean supports(Path file) {
        if (!file.toString().toLowerCase().endsWith(".json")) return false;
        try {
            JsonNode root = MAPPER.readTree(Files.readString(file));
            JsonNode first = root.isArray() && root.size() > 0 ? root.get(0) : root;
            return first.has("mapping");
        } catch (Exception e) {
            return false;
        }
    }

    @Override
    public String toMarkdown(Path file, ChatClient chatClient) throws Exception {
        JsonNode root = MAPPER.readTree(Files.readString(file));
        List<JsonNode> conversations = new ArrayList<>();
        if (root.isArray()) root.forEach(conversations::add);
        else conversations.add(root);

        var results = new StringBuilder();
        for (JsonNode conv : conversations) {
            results.append(convertOne(conv, chatClient)).append("\n\n---\n\n");
        }
        return results.toString().stripTrailing();
    }

    private String convertOne(JsonNode conv, ChatClient chatClient) {
        String title = conv.path("title").asText("Untitled conversation");
        StringBuilder transcript = new StringBuilder();
        // mapping values are unordered; collect and sort by create_time
        List<JsonNode> messages = new ArrayList<>();
        conv.path("mapping").fields().forEachRemaining(e -> {
            JsonNode msg = e.getValue().path("message");
            if (!msg.isMissingNode() && !msg.path("content").isMissingNode()) {
                messages.add(msg);
            }
        });
        messages.sort((a, b) -> Double.compare(
                a.path("create_time").asDouble(),
                b.path("create_time").asDouble()));

        for (JsonNode msg : messages) {
            String role = msg.path("author").path("role").asText();
            if (role.equals("system")) continue;
            StringBuilder text = new StringBuilder();
            msg.path("content").path("parts").forEach(p -> text.append(p.asText()));
            if (text.isEmpty()) continue;
            transcript.append(role.equals("user") ? "User: " : "Assistant: ")
                      .append(text).append("\n\n");
        }

        String truncated = transcript.length() > MAX_CHARS
                ? transcript.substring(0, MAX_CHARS) + "\n\n[... truncated ...]"
                : transcript.toString();

        return chatClient.prompt().user("""
                Summarize the following ChatGPT conversation as a wiki note.
                Extract: key insights, decisions, recommendations, and any code snippets.
                Add YAML frontmatter with the title "%s" and relevant tags.
                Output only the markdown file content, nothing else.

                Conversation:
                %s
                """.formatted(title, truncated)).call().content();
    }
}
