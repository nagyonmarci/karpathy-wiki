package com.danvega.wiki.lint;

import java.util.List;

/**
 * Combined lint output. Deterministic checks (orphans, brokenLinks,
 * gaps) are produced by {@link WikiScanner}. The {@code contradictions}
 * and {@code suggestions} fields are filled in by the LLM, which is
 * better suited to semantic reasoning.
 */
public record LintReport(
        List<String> orphans,
        List<BrokenLink> brokenLinks,
        List<String> gaps,
        List<String> contradictions,
        List<String> suggestions,
        String summary
) {
    public record BrokenLink(String fromFile, String missingTarget) {}
}
