package com.danvega.wiki.ingest;

import org.springframework.ai.chat.client.ChatClient;

import java.nio.file.Files;
import java.nio.file.Path;

class PlainTextConverter implements FileConverter {

    @Override
    public boolean supports(Path file) {
        String name = file.toString().toLowerCase();
        return name.endsWith(".txt") || name.endsWith(".md");
    }

    @Override
    public String toMarkdown(Path file, ChatClient chatClient) throws Exception {
        String content = Files.readString(file);
        // If it already has YAML frontmatter, return as-is
        if (content.startsWith("---")) {
            return content;
        }
        return chatClient.prompt().user("""
                The following is a plain text or Markdown note without YAML frontmatter.
                Add YAML frontmatter with title and tags inferred from the content.
                Keep the body content intact, only add the frontmatter block at the top.
                Output only the complete markdown file content, nothing else.

                Content:
                %s
                """.formatted(content)).call().content();
    }
}
