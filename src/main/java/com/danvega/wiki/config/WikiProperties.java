package com.danvega.wiki.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Filesystem and behavior configuration for the Karpathy Wiki.
 */
@ConfigurationProperties(prefix = "wiki")
public record WikiProperties(Paths paths, Ingest ingest) {

    public record Paths(String raw, String wiki, String skills, String memory) {}

    public record Ingest(boolean autoCompile) {}
}
