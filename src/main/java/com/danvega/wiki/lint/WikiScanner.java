package com.danvega.wiki.lint;

import com.danvega.wiki.config.WikiProperties;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Deterministic structural scan over wiki/. Produces ground-truth
 * orphan/broken-link/gap data so the LLM linter doesn't have to guess.
 */
@Component
public class WikiScanner {

    /** Pages shorter than this (in chars, body only) count as gaps/stubs. */
    private static final int STUB_THRESHOLD = 200;

    private static final Pattern WIKI_LINK = Pattern.compile("\\[\\[([^\\]|#]+)(?:[|#][^\\]]*)?]]");

    /** Files we ignore when computing orphans/links — they are scaffolding, not pages. */
    private static final Set<String> SPECIAL_FILES = Set.of("index.md", "log.md", "backlinks.md");

    private final Path wikiRoot;

    public WikiScanner(WikiProperties props) {
        this.wikiRoot = Path.of(props.paths().wiki());
    }

    public ScanResult scan() throws IOException {
        if (!Files.isDirectory(wikiRoot)) {
            return new ScanResult(List.of(), List.of(), List.of());
        }

        // 1. Index every .md page by its slug (filename without .md).
        Map<String, Path> pagesBySlug = new HashMap<>();
        List<Path> allPages = new ArrayList<>();
        try (Stream<Path> walk = Files.walk(wikiRoot)) {
            walk.filter(Files::isRegularFile)
                .filter(p -> p.toString().endsWith(".md"))
                .filter(p -> !SPECIAL_FILES.contains(p.getFileName().toString()))
                .forEach(p -> {
                    String slug = stripExt(p.getFileName().toString());
                    pagesBySlug.put(slug, p);
                    allPages.add(p);
                });
        }

        // 2. Walk every page collecting outbound links + inbound counts + body sizes.
        Map<Path, Integer> inboundCount = new HashMap<>();
        allPages.forEach(p -> inboundCount.put(p, 0));

        List<LintReport.BrokenLink> broken = new ArrayList<>();
        List<String> gaps = new ArrayList<>();

        for (Path page : allPages) {
            String content = Files.readString(page);
            String body = stripFrontMatter(content);
            if (body.strip().length() < STUB_THRESHOLD) {
                gaps.add(rel(page) + " (" + body.strip().length() + " chars)");
            }

            Matcher m = WIKI_LINK.matcher(content);
            while (m.find()) {
                String target = m.group(1).trim();
                Path resolved = pagesBySlug.get(target);
                if (resolved == null) {
                    broken.add(new LintReport.BrokenLink(rel(page), target));
                } else if (!resolved.equals(page)) {
                    inboundCount.merge(resolved, 1, Integer::sum);
                }
            }
        }

        // 3. Orphans = pages with zero inbound links (excluding articles, which
        //    are entry points and don't need inbound refs).
        List<String> orphans = new ArrayList<>();
        for (Path page : allPages) {
            if (page.toString().contains("/articles/")) continue;
            if (inboundCount.get(page) == 0) {
                orphans.add(rel(page));
            }
        }

        return new ScanResult(orphans, broken, gaps);
    }

    private String rel(Path p) {
        return wikiRoot.relativize(p).toString().replace('\\', '/');
    }

    private static String stripExt(String name) {
        int i = name.lastIndexOf('.');
        return i < 0 ? name : name.substring(0, i);
    }

    private static String stripFrontMatter(String content) {
        if (!content.startsWith("---")) return content;
        int end = content.indexOf("\n---", 3);
        if (end < 0) return content;
        return content.substring(end + 4);
    }

    public record ScanResult(
            List<String> orphans,
            List<LintReport.BrokenLink> brokenLinks,
            List<String> gaps
    ) {
        public boolean isClean() {
            return orphans.isEmpty() && brokenLinks.isEmpty() && gaps.isEmpty();
        }
    }
}
