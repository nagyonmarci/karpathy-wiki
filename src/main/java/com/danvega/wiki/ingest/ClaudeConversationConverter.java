package com.danvega.wiki.ingest;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.ai.chat.client.ChatClient;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

class ClaudeConversationConverter implements FileConverter {

    private static final int MAX_CHARS = 12_000;
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Override
    public boolean supports(Path file) {
        if (!file.toString().toLowerCase().endsWith(".json")) return false;
        try {
            JsonNode root = MAPPER.readTree(Files.readString(file));
            JsonNode first = root.isArray() && root.size() > 0 ? root.get(0) : root;
            return first.has("messages") && first.get("messages").isArray()
                    && first.get("messages").size() > 0
                    && first.get("messages").get(0).has("sender");
        } catch (Exception e) {
            return false;
        }
    }

    @Override
    public String toMarkdown(Path file, ChatClient chatClient) throws Exception {
        JsonNode root = MAPPER.readTree(Files.readString(file));
        // Export may be a single conversation or an array — normalise to array
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
        String title = conv.path("name").asText("Untitled conversation");
        StringBuilder transcript = new StringBuilder();
        for (JsonNode msg : conv.path("messages")) {
            String role = msg.path("sender").asText();
            String text = msg.path("text").asText();
            transcript.append(role.equals("human") ? "User: " : "Assistant: ")
                      .append(text).append("\n\n");
        }
        String truncated = transcript.length() > MAX_CHARS
                ? transcript.substring(0, MAX_CHARS) + "\n\n[... truncated ...]"
                : transcript.toString();

        return chatClient.prompt().user("""
                Summarize the following Claude conversation as a wiki note.
                Extract: key insights, decisions, recommendations, and any code snippets.
                Add YAML frontmatter with the title "%s" and relevant tags.
                Output only the markdown file content, nothing else.

                Conversation:
                %s
                """.formatted(title, truncated)).call().content();
    }
}
