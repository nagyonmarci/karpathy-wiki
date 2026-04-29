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
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
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
        try (Stream<Path> stream = Files.walk(importDir)) {
            files = stream
                    .filter(Files::isRegularFile)
                    .filter(p -> !p.startsWith(processedDir))
                    .toList();
        }

        if (files.isEmpty()) {
            return "No files found in import/ — nothing to process.";
        }

        int n = effectiveParallelism();
        log.info("Processing {} file(s) with parallelism={}", files.size(), n);

        AtomicInteger ok = new AtomicInteger();
        AtomicInteger failed = new AtomicInteger();

        List<Callable<Void>> tasks = files.stream()
                .<Callable<Void>>map(file -> () -> {
                    if (processFile(file, importDir, processedDir, rawDir)) ok.incrementAndGet();
                    else failed.incrementAndGet();
                    return null;
                })
                .toList();

        ExecutorService pool = Executors.newFixedThreadPool(n);
        try {
            List<Future<Void>> futures = pool.invokeAll(tasks);
            for (Future<Void> f : futures) f.get(); // propagate exceptions
        } finally {
            pool.shutdown();
        }

        return "Import complete: %d processed, %d failed.".formatted(ok.get(), failed.get());
    }

    private boolean processFile(Path file, Path importDir, Path processedDir, Path rawDir) {
        FileConverter converter = converters.stream()
                .filter(c -> c.supports(file))
                .findFirst()
                .orElse(null);
        if (converter == null) {
            log.warn("No converter for {}, skipping", file.getFileName());
            return false;
        }
        try {
            String markdown = converter.toMarkdown(file, chatClient);
            String slug = slugify(converter.inferTitle(file));
            Path out = rawDir.resolve(LocalDate.now() + "-" + slug + ".md");
            Files.writeString(out, markdown);
            Path rel = importDir.relativize(file);
            Path dest = processedDir.resolve(rel);
            Files.createDirectories(dest.getParent());
            Files.move(file, dest);
            log.info("Imported {} → {}", file.getFileName(), out.getFileName());
            return true;
        } catch (Exception e) {
            log.error("Failed to import {}: {}", file.getFileName(), e.getMessage());
            return false;
        }
    }

    private int effectiveParallelism() {
        int cfg = props.ingest().parallelism();
        if (cfg > 0) return cfg;
        // Mirror Ollama's server-side batch capacity — VRAM/model-size is the real ceiling, not CPU cores
        String env = System.getenv("OLLAMA_NUM_PARALLEL");
        if (env != null) {
            try { return Math.max(1, Integer.parseInt(env.trim())); } catch (NumberFormatException ignored) {}
        }
        return 2; // matches docker-compose default OLLAMA_NUM_PARALLEL=2
    }

    private static String slugify(String input) {
        String s = input.toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9]+", "-")
                .replaceAll("(^-|-$)", "");
        return s.length() > 80 ? s.substring(0, 80) : s;
    }
}
