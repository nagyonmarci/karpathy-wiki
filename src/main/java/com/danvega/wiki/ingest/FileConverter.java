package com.danvega.wiki.ingest;

import org.springframework.ai.chat.client.ChatClient;

import java.nio.file.Path;

interface FileConverter {
    boolean supports(Path file);
    String toMarkdown(Path file, ChatClient chatClient) throws Exception;
    default String inferTitle(Path file) {
        String name = file.getFileName().toString();
        int dot = name.lastIndexOf('.');
        return dot > 0 ? name.substring(0, dot) : name;
    }
}
