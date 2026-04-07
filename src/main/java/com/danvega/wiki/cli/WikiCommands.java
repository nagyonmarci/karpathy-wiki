package com.danvega.wiki.cli;

import com.danvega.wiki.compile.WikiCompilerAgent;
import com.danvega.wiki.config.WikiProperties;
import com.danvega.wiki.ingest.IngestService;
import com.danvega.wiki.lint.LintReport;
import com.danvega.wiki.lint.WikiLinterAgent;
import com.danvega.wiki.log.OperationLog;
import com.danvega.wiki.research.ResearchAgent;
import org.springframework.shell.standard.ShellComponent;
import org.springframework.shell.standard.ShellMethod;
import org.springframework.shell.standard.ShellOption;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;

@ShellComponent
public class WikiCommands {

    private final IngestService ingestService;
    private final WikiCompilerAgent compiler;
    private final WikiLinterAgent linter;
    private final ResearchAgent research;
    private final WikiProperties props;
    private final OperationLog log;

    public WikiCommands(IngestService ingestService,
                        WikiCompilerAgent compiler,
                        WikiLinterAgent linter,
                        ResearchAgent research,
                        WikiProperties props,
                        OperationLog log) {
        this.ingestService = ingestService;
        this.compiler = compiler;
        this.linter = linter;
        this.research = research;
        this.props = props;
        this.log = log;
    }

    @ShellMethod(key = "ingest", value = "Fetch a URL, convert to Markdown, save to raw/ (auto-compiles if enabled).")
    public String ingest(
            @ShellOption(value = {"--url"}, help = "Source URL to ingest") String url,
            @ShellOption(value = {"--title"}, help = "Optional title", defaultValue = ShellOption.NULL) String title,
            @ShellOption(value = {"--tags"}, help = "Comma-separated tags", defaultValue = ShellOption.NULL) String tags
    ) throws Exception {
        List<String> tagList = tags == null ? List.of() : Arrays.stream(tags.split(",")).map(String::trim).filter(s -> !s.isEmpty()).toList();
        Path saved = ingestService.ingestUrl(url, title, tagList);
        log.append("ingest", "saved " + saved.getFileName() + " from " + url);
        StringBuilder out = new StringBuilder("Saved: ").append(saved);
        if (props.ingest().autoCompile()) {
            String compileResult = compiler.compile();
            log.append("compile", compileResult);
            out.append("\n\nCompiled:\n").append(compileResult);
        }
        return out.toString();
    }

    @ShellMethod(key = "compile", value = "Compile raw/ files into structured wiki content.")
    public String compile() {
        String result = compiler.compile();
        log.append("compile", result);
        return result;
    }

    @ShellMethod(key = "lint", value = "Lint the wiki for orphans, broken links, gaps, and contradictions.")
    public String lint() {
        LintReport report = linter.lint();
        // LintReport fields come from LLM-deserialized JSON; any list may be null if the model omits it.
        List<String> orphans = report.orphans() == null ? List.of() : report.orphans();
        List<LintReport.BrokenLink> brokenLinks = report.brokenLinks() == null ? List.of() : report.brokenLinks();
        List<String> gaps = report.gaps() == null ? List.of() : report.gaps();
        List<String> contradictions = report.contradictions() == null ? List.of() : report.contradictions();

        log.append("lint", "%d orphans, %d broken, %d gaps, %d contradictions"
                .formatted(orphans.size(), brokenLinks.size(), gaps.size(), contradictions.size()));
        StringBuilder sb = new StringBuilder();
        sb.append("Orphans (").append(orphans.size()).append("):\n");
        orphans.forEach(o -> sb.append("  - ").append(o).append('\n'));
        sb.append("\nBroken links (").append(brokenLinks.size()).append("):\n");
        brokenLinks.forEach(b -> sb.append("  - ").append(b).append('\n'));
        sb.append("\nGaps (").append(gaps.size()).append("):\n");
        gaps.forEach(g -> sb.append("  - ").append(g).append('\n'));
        sb.append("\nContradictions (").append(contradictions.size()).append("):\n");
        contradictions.forEach(c -> sb.append("  - ").append(c).append('\n'));
        return sb.toString();
    }

    @ShellMethod(key = "query", value = "Ask a natural-language question against the wiki.")
    public String query(@ShellOption(value = {"--question"}, help = "Question to ask") String question) {
        ResearchAgent.QueryResult result = research.query(question);
        log.append("query", question + " → " + result.sources().size() + " sources");
        StringBuilder sb = new StringBuilder(result.answer()).append("\n\nSources:\n");
        result.sources().forEach(s -> sb.append("  - ").append(s).append('\n'));
        return sb.toString();
    }

    @ShellMethod(key = "status", value = "Show wiki/raw file counts and configured paths.")
    public String status() throws Exception {
        long raw = count(props.paths().raw());
        long wiki = count(props.paths().wiki());
        return """
                raw files:  %d  (%s)
                wiki files: %d  (%s)
                skills dir: %s
                memory dir: %s
                """.formatted(raw, props.paths().raw(), wiki, props.paths().wiki(), props.paths().skills(), props.paths().memory());
    }

    private long count(String dir) throws Exception {
        Path p = Path.of(dir);
        if (!Files.exists(p)) return 0;
        try (var s = Files.walk(p)) {
            return s.filter(Files::isRegularFile).count();
        }
    }
}
