package com.danvega.wiki.compile;

import com.danvega.wiki.config.SchemaLoader;
import org.springaicommunity.agent.advisors.AutoMemoryToolsAdvisor;
import org.springaicommunity.agent.tools.FileSystemTools;
import org.springaicommunity.agent.tools.GlobTool;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Compiles raw content (in {@code raw/}) into clean linked Markdown
 * (concepts, articles, summaries, index, backlinks) under {@code wiki/}.
 */
@Component
public class WikiCompilerAgent {

    private static final String SYSTEM_PROMPT = """
            You are the WikiCompilerAgent for a personal Markdown knowledge base.

            Your job:
              1. Read raw files from the `raw/` directory (use the filesystem tools).
              2. For each new or updated raw file, produce/refresh clean Markdown
                 in `wiki/` with this structure:
                   - wiki/articles/<slug>.md   (main writeup)
                   - wiki/concepts/<concept>.md (per concept, dedupe across articles)
                   - wiki/summaries/<slug>.md  (short TL;DR)
              3. Maintain wiki/index.md and wiki/backlinks.md.
              4. Use front-matter: title, summary, concepts, sources, backlinks.
              5. Cross-link aggressively using `[[wiki-links]]` style.
              6. Consult skills (style-guide.md, article-template.md) before writing.
              7. Be incremental: do not rewrite files that have not changed.

            Filesystem is the source of truth. Write clean, consistent Markdown.
            """;

    private final ChatClient chatClient;
    private final RawPreprocessor preprocessor;

    public WikiCompilerAgent(ChatClient.Builder builder,
                             FileSystemTools fs,
                             GlobTool glob,
                             ToolCallback skillsTool,
                             AutoMemoryToolsAdvisor memoryAdvisor,
                             RawPreprocessor preprocessor,
                             SchemaLoader schemaLoader) {
        this.preprocessor = preprocessor;
        this.chatClient = builder
                .defaultSystem(schemaLoader.asSystemBlock() + SYSTEM_PROMPT)
                .defaultTools(fs, glob)
                .defaultToolCallbacks(skillsTool)
                .defaultAdvisors(memoryAdvisor)
                .build();
    }

    public String compile() {
        List<Path> changed = findChangedOrThrow();
        if (changed.isEmpty()) {
            return "Nothing to compile — no new or modified files in raw/.";
        }

        String userMessage = buildUserMessage(changed);
        String result = chatClient.prompt().user(userMessage).call().content();

        commitOrThrow(changed);
        return result;
    }

    static String buildUserMessage(List<Path> changed) {
        String fileList = changed.stream()
                .map(p -> "- raw/" + p.getFileName())
                .collect(Collectors.joining("\n"));

        return """
                The following files in raw/ are new or have changed since the
                last compile. Read each one and integrate it into the wiki/
                directory now (articles, concepts, summaries, index, backlinks):

                %s
                """.formatted(fileList);
    }

    private List<Path> findChangedOrThrow() {
        try {
            return preprocessor.findChanged();
        } catch (Exception e) {
            throw new RuntimeException("Raw preprocessing failed", e);
        }
    }

    private void commitOrThrow(List<Path> changed) {
        try {
            preprocessor.commit(changed);
        } catch (Exception e) {
            throw new RuntimeException("Updating compile manifest failed", e);
        }
    }
}
