package com.danvega.wiki.config;

import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Loads SCHEMA.md once at startup. Every agent prepends this to its
 * system prompt, so the wiki's structure and workflows live in one
 * editable file rather than scattered across Java prompts.
 */
@Component
public class SchemaLoader {

    private final String schema;

    public SchemaLoader() {
        Path file = Path.of("SCHEMA.md");
        String body;
        try {
            body = Files.exists(file) ? Files.readString(file) : "";
        } catch (Exception e) {
            body = "";
        }
        this.schema = body;
    }

    /** Returns SCHEMA.md verbatim, or "" if it doesn't exist. */
    public String schema() {
        return schema;
    }

    /** Convenience: schema framed as a system-prompt block, or empty string. */
    public String asSystemBlock() {
        if (schema.isBlank()) return "";
        return "Authoritative wiki schema (SCHEMA.md). Follow it strictly:\n\n" + schema + "\n\n---\n\n";
    }
}
