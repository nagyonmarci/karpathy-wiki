package com.danvega.wiki.ingest;

import com.danvega.wiki.config.WikiProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Service;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;

@Service
public class ImportService {

    private static final Logger log = LoggerFactory.getLogger(ImportService.class);

    private final ChatClient chatClient;
    private final WikiProperties props;
    private final List<FileConverter> converters;

    public ImportService(ChatClient.Builder chatClientBuilder, WikiProperties props) {
        this.chatClient = chatClientBuilder.build();
        this.props = props;
        this.converters = List.of(
                new PdfConverter(),
                new DocxConverter(),
                new ClaudeConversationConverter(),
                new ChatGptConversationConverter(),
                new PlainTextConverter()
        );
    }

    public String processAll() throws Exception {
        Path importDir = Path.of(props.paths().imports());
        Path processedDir = importDir.resolve("processed");
        Path rawDir = Path.of(props.paths().raw());
        Files.createDirectories(processedDir);
        Files.createDirectories(rawDir);

        if (!Files.exists(importDir)) {
            return "import/ directory not found — nothing to process.";
        }

        List<Path> files;
        try (Stream<Path> stream = Files.list(importDir)) {
            files = stream.filter(Files::isRegularFile).toList();
        }

        if (files.isEmpty()) {
            return "No files found in import/ — nothing to process.";
        }

        int ok = 0, failed = 0;
        for (Path file : files) {
            FileConverter converter = converters.stream()
                    .filter(c -> c.supports(file))
                    .findFirst()
                    .orElse(null);
            if (converter == null) {
                log.warn("No converter for {}, skipping", file.getFileName());
                continue;
            }
            try {
                String markdown = converter.toMarkdown(file, chatClient);
                String slug = slugify(converter.inferTitle(file));
                Path out = rawDir.resolve(LocalDate.now() + "-" + slug + ".md");
                Files.writeString(out, markdown);
                Files.move(file, processedDir.resolve(file.getFileName()));
                log.info("Imported {} → {}", file.getFileName(), out.getFileName());
                ok++;
            } catch (Exception e) {
                log.error("Failed to import {}: {}", file.getFileName(), e.getMessage());
                failed++;
            }
        }
        return "Import complete: %d processed, %d failed.".formatted(ok, failed);
    }

    private static String slugify(String input) {
        String s = input.toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9]+", "-")
                .replaceAll("(^-|-$)", "");
        return s.length() > 80 ? s.substring(0, 80) : s;
    }
}
