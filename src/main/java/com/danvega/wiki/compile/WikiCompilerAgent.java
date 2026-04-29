package com.danvega.wiki.compile;

import com.danvega.wiki.config.SchemaLoader;
import com.danvega.wiki.ingest.RepoResolver;
import org.springaicommunity.agent.advisors.AutoMemoryToolsAdvisor;
import org.springaicommunity.agent.tools.FileSystemTools;
import org.springaicommunity.agent.tools.GlobTool;
import org.springaicommunity.agent.tools.GrepTool;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
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
              7. IMPORTANT — source code repos: If a cloned repo path is provided
                 in the user message, you MUST use glob and grep to explore the
                 repo directory, find the key source files (e.g. *.java, *.kt,
                 *.py), read them, and include actual code snippets in fenced
                 code blocks in the articles and concepts you produce. Articles
                 about code MUST contain real code examples — never just describe
                 what the code does in prose.
              8. Be incremental: do not rewrite files that have not changed.

            Filesystem is the source of truth. Write clean, consistent Markdown.
            """;

    private final ChatClient chatClient;
    private final RawPreprocessor preprocessor;
    private final RepoResolver repoResolver;

    public WikiCompilerAgent(ChatClient.Builder builder,
                             FileSystemTools fs,
                             GlobTool glob,
                             GrepTool grep,
                             ToolCallback skillsTool,
                             AutoMemoryToolsAdvisor memoryAdvisor,
                             RawPreprocessor preprocessor,
                             RepoResolver repoResolver,
                             SchemaLoader schemaLoader) {
        this.preprocessor = preprocessor;
        this.repoResolver = repoResolver;
        this.chatClient = builder
                .defaultSystem(schemaLoader.asSystemBlock() + SYSTEM_PROMPT)
                .defaultTools(fs, glob, grep)
                .defaultToolCallbacks(skillsTool)
                .defaultAdvisors(memoryAdvisor)
                .build();
    }

    public String compile() {
        List<Path> changed = findChangedOrThrow();
        if (changed.isEmpty()) {
            return "Nothing to compile — no new or modified files in raw/.";
        }

        try {
            Map<String, Path> repoMap = resolveReposOrThrow(changed);

            String userMessage = buildUserMessage(changed, repoMap);
            String result = chatClient.prompt().user(userMessage).call().content();

            commitOrThrow(changed);
            return result;
        } finally {
            repoResolver.cleanup();
        }
    }

    static String buildUserMessage(List<Path> changed, Map<String, Path> repoMap) {
        String fileList = changed.stream()
                .map(p -> "- raw/" + p.getFileName())
                .collect(Collectors.joining("\n"));

        StringBuilder sb = new StringBuilder();
        sb.append("""
                The following files in raw/ are new or have changed since the
                last compile. Read each one and integrate it into the wiki/
                directory now (articles, concepts, summaries, index, backlinks):

                %s
                """.formatted(fileList));

        if (!repoMap.isEmpty()) {
            sb.append("\nThe following GitHub repos have been cloned locally. ");
            sb.append("When a raw file has a `repo` field matching one of these URLs, ");
            sb.append("read source code from the local path and include actual code ");
            sb.append("snippets in the articles and concepts you produce:\n\n");
            for (var entry : repoMap.entrySet()) {
                sb.append("- ").append(entry.getKey()).append(" → ")
                        .append(entry.getValue().toAbsolutePath()).append("\n");
            }
        }

        return sb.toString();
    }

    private List<Path> findChangedOrThrow() {
        try {
            return preprocessor.findChanged();
        } catch (Exception e) {
            throw new RuntimeException("Raw preprocessing failed", e);
        }
    }

    private Map<String, Path> resolveReposOrThrow(List<Path> changed) {
        try {
            return repoResolver.resolveRepos(changed);
        } catch (Exception e) {
            throw new RuntimeException("Repo resolution failed", e);
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
