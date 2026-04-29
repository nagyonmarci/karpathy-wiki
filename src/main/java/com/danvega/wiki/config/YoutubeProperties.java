package com.danvega.wiki.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "wiki.youtube")
public record YoutubeProperties(
        String fetcherUrl,
        String directusUrl,
        String directusToken,
        int pollIntervalSeconds,
        int timeoutSeconds
) {}
