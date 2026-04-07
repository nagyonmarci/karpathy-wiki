package com.danvega.wiki.log;

import com.danvega.wiki.config.WikiProperties;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * Append-only chronological record of every ingest/compile/query/lint
 * operation. Karpathy's gist calls this out as one of the wiki's two
 * "special files" (alongside index.md).
 */
@Component
public class OperationLog {

    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    private final Path logFile;

    public OperationLog(WikiProperties props) {
        this.logFile = Path.of(props.paths().wiki(), "log.md");
    }

    public synchronized void append(String op, String summary) {
        String oneLine = summary == null ? "" : summary.replaceAll("\\s+", " ").trim();
        if (oneLine.length() > 200) {
            oneLine = oneLine.substring(0, 197) + "...";
        }
        String line = "- %s **%s** — %s%n".formatted(LocalDateTime.now().format(TS), op, oneLine);
        try {
            Files.createDirectories(logFile.getParent());
            if (!Files.exists(logFile)) {
                Files.writeString(logFile, "# Operation Log\n\n");
            }
            Files.writeString(logFile, line, StandardOpenOption.APPEND);
        } catch (IOException e) {
            throw new RuntimeException("Failed to append to " + logFile, e);
        }
    }
}
