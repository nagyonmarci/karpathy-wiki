package com.danvega.wiki.lint;

import com.danvega.wiki.config.WikiProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

class WikiScannerTest {

    @TempDir
    Path wikiRoot;

    private WikiScanner scanner;

    @BeforeEach
    void setUp() {
        WikiProperties props = new WikiProperties(
                new WikiProperties.Paths("raw", wikiRoot.toString(), "skills", "memory", "./import"),
                new WikiProperties.Ingest(false));
        scanner = new WikiScanner(props);
    }

    private Path write(String relative, String content) throws IOException {
        Path p = wikiRoot.resolve(relative);
        Files.createDirectories(p.getParent() == null ? wikiRoot : p.getParent());
        Files.writeString(p, content);
        return p;
    }

    /** Pad a body so it clears the 200-char stub threshold. */
    private static String body(String prefix) {
        return prefix + " " + "x".repeat(220);
    }

    @Test
    void scan_emptyOrMissingRoot_returnsEmptyResult() throws IOException {
        WikiProperties props = new WikiProperties(
                new WikiProperties.Paths("raw", wikiRoot.resolve("does-not-exist").toString(),
                        "skills", "memory", "./import"),
                new WikiProperties.Ingest(false));
        WikiScanner s = new WikiScanner(props);

        WikiScanner.ScanResult result = s.scan();

        assertThat(result.orphans()).isEmpty();
        assertThat(result.brokenLinks()).isEmpty();
        assertThat(result.gaps()).isEmpty();
        assertThat(result.isClean()).isTrue();
    }

    @Nested
    class LinkExtraction {

        @Test
        void simpleLink_isResolved() throws IOException {
            write("a.md", body("links to [[b]]"));
            write("b.md", body("target page"));

            WikiScanner.ScanResult r = scanner.scan();

            assertThat(r.brokenLinks()).isEmpty();
            assertThat(r.orphans()).containsExactly("a.md"); // b has inbound, a does not
        }

        @Test
        void linkWithAlias_isResolved() throws IOException {
            write("a.md", body("see [[b|the B page]]"));
            write("b.md", body("target"));

            WikiScanner.ScanResult r = scanner.scan();

            assertThat(r.brokenLinks()).isEmpty();
        }

        @Test
        void linkWithAnchor_isResolved() throws IOException {
            write("a.md", body("jump to [[b#section]]"));
            write("b.md", body("target"));

            WikiScanner.ScanResult r = scanner.scan();

            assertThat(r.brokenLinks()).isEmpty();
        }

        @Test
        void multipleLinksOnOneLine_allResolved() throws IOException {
            write("a.md", body("[[b]] and [[c]] and [[b|alt]]"));
            write("b.md", body("b"));
            write("c.md", body("c"));

            WikiScanner.ScanResult r = scanner.scan();

            assertThat(r.brokenLinks()).isEmpty();
        }

        @Test
        void brokenLink_isReported() throws IOException {
            write("a.md", body("link to [[ghost]]"));

            WikiScanner.ScanResult r = scanner.scan();

            assertThat(r.brokenLinks())
                    .extracting(LintReport.BrokenLink::fromFile, LintReport.BrokenLink::missingTarget)
                    .containsExactly(tuple("a.md", "ghost"));
        }

        @Test
        void caseSensitive_differentCaseIsBroken() throws IOException {
            write("a.md", body("link [[Bee]]"));
            write("bee.md", body("the bee"));

            WikiScanner.ScanResult r = scanner.scan();

            assertThat(r.brokenLinks()).hasSize(1);
            assertThat(r.brokenLinks().get(0).missingTarget()).isEqualTo("Bee");
        }

        @Test
        void selfLink_doesNotCountAsInbound() throws IOException {
            write("a.md", body("self ref [[a]] more text"));

            WikiScanner.ScanResult r = scanner.scan();

            // a links only to itself; should still be orphan (no external inbound).
            assertThat(r.orphans()).containsExactly("a.md");
            assertThat(r.brokenLinks()).isEmpty();
        }
    }

    @Nested
    class FrontMatterStripping {

        @Test
        void validFrontMatter_isStrippedForStubMeasurement() throws IOException {
            // Front-matter is large, body is tiny → should still count as a gap.
            String fm = "---\ntitle: Foo\ntags: [a, b, c, d, e]\nauthor: someone\n"
                    + "description: " + "x".repeat(300) + "\n---\nshort body\n";
            write("a.md", fm);

            WikiScanner.ScanResult r = scanner.scan();

            assertThat(r.gaps()).hasSize(1);
            assertThat(r.gaps().get(0)).startsWith("a.md");
        }

        @Test
        void noFrontMatter_bodyMeasuredDirectly() throws IOException {
            write("a.md", body("plain content"));

            WikiScanner.ScanResult r = scanner.scan();

            assertThat(r.gaps()).isEmpty();
        }

        @Test
        void unterminatedFrontMatter_treatedAsBody() throws IOException {
            // No closing ---, so the whole file is the body. It's long enough to clear the threshold.
            String content = "---\ntitle: oops\n" + "x".repeat(300);
            write("a.md", content);

            WikiScanner.ScanResult r = scanner.scan();

            assertThat(r.gaps()).isEmpty();
        }
    }

    @Nested
    class StubDetection {

        @Test
        void emptyFile_isGap() throws IOException {
            write("a.md", "");

            WikiScanner.ScanResult r = scanner.scan();

            assertThat(r.gaps()).hasSize(1);
            assertThat(r.gaps().get(0)).contains("(0 chars)");
        }

        @Test
        void whitespaceOnly_isGap() throws IOException {
            write("a.md", "   \n\t  \n");

            WikiScanner.ScanResult r = scanner.scan();

            assertThat(r.gaps()).hasSize(1);
        }

        @Test
        void justUnderThreshold_isGap() throws IOException {
            write("a.md", "x".repeat(199));

            WikiScanner.ScanResult r = scanner.scan();

            assertThat(r.gaps()).hasSize(1);
        }

        @Test
        void atThreshold_isNotGap() throws IOException {
            write("a.md", "x".repeat(200));

            WikiScanner.ScanResult r = scanner.scan();

            assertThat(r.gaps()).isEmpty();
        }
    }

    @Nested
    class OrphanDetection {

        @Test
        void chain_onlyHeadIsOrphan() throws IOException {
            write("a.md", body("start [[b]]"));
            write("b.md", body("middle [[c]]"));
            write("c.md", body("end"));

            WikiScanner.ScanResult r = scanner.scan();

            assertThat(r.orphans()).containsExactly("a.md");
        }

        @Test
        void isolatedNode_isOrphan() throws IOException {
            write("a.md", body("alone"));

            WikiScanner.ScanResult r = scanner.scan();

            assertThat(r.orphans()).containsExactly("a.md");
        }

        @Test
        void articlesAreNotConsideredOrphans() throws IOException {
            write("articles/intro.md", body("entry point with no inbound links"));
            write("b.md", body("alone"));

            WikiScanner.ScanResult r = scanner.scan();

            assertThat(r.orphans()).containsExactly("b.md");
        }

        @Test
        void specialFilesAreIgnored() throws IOException {
            // index.md, log.md, backlinks.md should not be indexed as pages,
            // and should not count toward orphan/gap/link analysis.
            write("index.md", "");
            write("log.md", "");
            write("backlinks.md", "");
            write("a.md", body("real page"));

            WikiScanner.ScanResult r = scanner.scan();

            assertThat(r.orphans()).containsExactly("a.md");
            assertThat(r.gaps()).extracting(s -> s.split(" ")[0])
                    .doesNotContain("index.md", "log.md", "backlinks.md");
        }
    }

    @Test
    void relativePathsUseForwardSlashes() throws IOException {
        write("concepts/nested/a.md", body("alone"));

        WikiScanner.ScanResult r = scanner.scan();

        assertThat(r.orphans()).containsExactly("concepts/nested/a.md");
    }
}
