package com.danvega.wiki.research;

import com.danvega.wiki.config.SchemaLoader;
import org.springaicommunity.agent.advisors.AutoMemoryToolsAdvisor;
import org.springaicommunity.agent.tools.FileSystemTools;
import org.springaicommunity.agent.tools.GlobTool;
import org.springaicommunity.agent.tools.GrepTool;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Answers natural-language questions using the wiki as primary source,
 * and writes new research outputs (notes, comparisons, Marp slides, etc.)
 * into {@code wiki/outputs/}.
 */
@Component
public class ResearchAgent {

    private static final String SYSTEM_PROMPT = """
            You are the ResearchAgent for a personal Markdown knowledge base.

            For every user question:
              1. Search wiki/ first (use grep/glob/read tools).
              2. Synthesize a clear, well-cited answer.
              3. When the user asks for a "note", "comparison", "slides", or
                 similar artifact, write a Markdown file under wiki/outputs/.
                 Use Mermaid for diagrams and Marp front-matter for slides.
              4. ALWAYS populate the `sources` array with the wiki-relative
                 paths of every file you actually read to answer the question
                 (e.g. "wiki/articles/react.md"). Never leave sources empty
                 unless you genuinely could not find anything in the wiki.
              5. If wiki coverage is thin, say so in `answer` and suggest what
                 should be ingested next.
            """;

    /** Structured response: prose answer plus the wiki files cited. */
    public record QueryResult(String answer, List<String> sources) {}

    private final ChatClient chatClient;

    public ResearchAgent(ChatClient.Builder builder,
                         FileSystemTools fs,
                         GrepTool grep,
                         GlobTool glob,
                         ToolCallback skillsTool,
                         AutoMemoryToolsAdvisor memoryAdvisor,
                         SchemaLoader schemaLoader) {
        this.chatClient = builder
                .defaultSystem(schemaLoader.asSystemBlock() + SYSTEM_PROMPT)
                .defaultTools(fs, grep, glob)
                .defaultToolCallbacks(skillsTool)
                .defaultAdvisors(memoryAdvisor)
                .build();
    }

    public QueryResult query(String question) {
        return chatClient.prompt().user(question).call().entity(QueryResult.class);
    }
}
