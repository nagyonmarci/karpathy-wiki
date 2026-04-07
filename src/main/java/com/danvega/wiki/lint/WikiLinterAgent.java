package com.danvega.wiki.lint;

import com.danvega.wiki.config.SchemaLoader;
import org.springaicommunity.agent.advisors.AutoMemoryToolsAdvisor;
import org.springaicommunity.agent.tools.FileSystemTools;
import org.springaicommunity.agent.tools.GlobTool;
import org.springaicommunity.agent.tools.GrepTool;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.stream.Collectors;

/**
 * Keeps the wiki healthy. Runs a deterministic structural pre-pass
 * (orphans, broken links, stub pages) via {@link WikiScanner}, then
 * asks the LLM to layer on semantic checks (contradictions, suggested
 * connections) and rebuild backlinks.
 */
@Component
public class WikiLinterAgent {

    private static final String SYSTEM_PROMPT = """
            You are the WikiLinterAgent. Your job is to keep wiki/ healthy.

            On each run you receive a deterministic LintReport from a
            structural scan (orphans, broken links, stubs). You MUST:
              1. Treat that report as ground truth for those three categories.
              2. Additionally identify CONTRADICTIONS — pages that disagree
                 with each other on the same topic. Use grep/read to verify.
              3. Suggest 3-5 high-value new connections per run (suggestions).
              4. Rebuild wiki/backlinks.md from the [[wiki-link]] graph.
              5. Enforce skills/style-guide if present.
              6. Return a structured LintReport: copy the orphans/brokenLinks/
                 gaps you were given, then fill in contradictions, suggestions,
                 and a one-paragraph summary of what you changed.
            """;

    private final ChatClient chatClient;
    private final WikiScanner scanner;

    public WikiLinterAgent(ChatClient.Builder builder,
                           FileSystemTools fs,
                           GrepTool grep,
                           GlobTool glob,
                           ToolCallback skillsTool,
                           AutoMemoryToolsAdvisor memoryAdvisor,
                           SchemaLoader schemaLoader,
                           WikiScanner scanner) {
        this.scanner = scanner;
        this.chatClient = builder
                .defaultSystem(schemaLoader.asSystemBlock() + SYSTEM_PROMPT)
                .defaultTools(fs, grep, glob)
                .defaultToolCallbacks(skillsTool)
                .defaultAdvisors(memoryAdvisor)
                .build();
    }

    public LintReport lint() {
        WikiScanner.ScanResult scan;
        try {
            scan = scanner.scan();
        } catch (Exception e) {
            throw new RuntimeException("Wiki scan failed", e);
        }

        String prepass = """
                Deterministic pre-pass results (treat as ground truth):

                ORPHANS (%d):
                %s

                BROKEN LINKS (%d):
                %s

                STUB / GAP PAGES (%d):
                %s

                Now: rebuild wiki/backlinks.md, look for contradictions across
                pages on the same topic (verify with grep/read), suggest 3-5
                new high-value connections, and return the full LintReport.
                Copy the three lists above into the report verbatim.
                """.formatted(
                        scan.orphans().size(), bullets(scan.orphans()),
                        scan.brokenLinks().size(),
                        bullets(scan.brokenLinks().stream()
                                .map(b -> b.fromFile() + " → [[" + b.missingTarget() + "]]")
                                .collect(Collectors.toList())),
                        scan.gaps().size(), bullets(scan.gaps())
                );

        return chatClient.prompt().user(prepass).call().entity(LintReport.class);
    }

    private static String bullets(List<String> items) {
        if (items.isEmpty()) return "  (none)";
        return items.stream().map(s -> "  - " + s).collect(Collectors.joining("\n"));
    }
}
