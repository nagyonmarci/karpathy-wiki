package com.danvega.wiki;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

/**
 * Karpathy Wiki - LLM-powered self-maintaining Markdown knowledge base.
 *
 * <p>Inspired by Andrej Karpathy's idea of a personal wiki where an LLM
 * does ~95% of the curation work: ingesting raw content, compiling it
 * into clean linked Markdown, answering research questions, and
 * keeping the knowledge base healthy.</p>
 */
@SpringBootApplication
@ConfigurationPropertiesScan
public class KarpathyWikiApplication {

    static void main(String[] args) {
        SpringApplication.run(KarpathyWikiApplication.class, args);
    }
}
