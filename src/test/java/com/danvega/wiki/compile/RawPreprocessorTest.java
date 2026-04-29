package com.danvega.wiki.compile;

import com.danvega.wiki.config.WikiProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class RawPreprocessorTest {

    @TempDir
    Path tempDir;

    Path rawDir;
    RawPreprocessor preprocessor;

    @BeforeEach
    void setUp() throws Exception {
        rawDir = tempDir.resolve("raw");
        Files.createDirectories(rawDir);
        WikiProperties props = new WikiProperties(
                new WikiProperties.Paths(rawDir.toString(), tempDir.resolve("wiki").toString(),
                        "skills", "memory", "./import"),
                new WikiProperties.Ingest(false, 1));
        preprocessor = new RawPreprocessor(props);
    }

    private Path writeRaw(String name, String content) throws Exception {
        Path p = rawDir.resolve(name);
        Files.writeString(p, content);
        return p;
    }

    @Test
    void findChanged_missingRawDir_returnsEmpty() throws Exception {
        WikiProperties props = new WikiProperties(
                new WikiProperties.Paths(tempDir.resolve("nope").toString(),
                        tempDir.resolve("wiki").toString(), "skills", "memory", "./import"),
                new WikiProperties.Ingest(false, 1));
        RawPreprocessor p = new RawPreprocessor(props);

        assertThat(p.findChanged()).isEmpty();
    }

    @Test
    void findChanged_emptyDir_returnsEmpty() throws Exception {
        assertThat(preprocessor.findChanged()).isEmpty();
    }

    @Test
    void findChanged_newFiles_areReturned() throws Exception {
        writeRaw("a.md", "alpha");
        writeRaw("b.md", "beta");

        List<Path> changed = preprocessor.findChanged();

        assertThat(changed).extracting(p -> p.getFileName().toString())
                .containsExactlyInAnyOrder("a.md", "b.md");
    }

    @Test
    void commit_thenFindChanged_returnsNothing() throws Exception {
        writeRaw("a.md", "alpha");
        List<Path> first = preprocessor.findChanged();
        preprocessor.commit(first);

        assertThat(preprocessor.findChanged()).isEmpty();
    }

    @Test
    void commit_thenModifyFile_isDetectedAsChanged() throws Exception {
        Path a = writeRaw("a.md", "alpha");
        preprocessor.commit(List.of(a));

        Files.writeString(a, "alpha modified");

        assertThat(preprocessor.findChanged())
                .extracting(p -> p.getFileName().toString())
                .containsExactly("a.md");
    }

    @Test
    void commit_identicalContent_notReportedChanged() throws Exception {
        Path a = writeRaw("a.md", "same content");
        preprocessor.commit(List.of(a));
        // Rewrite with identical bytes.
        Files.writeString(a, "same content");

        assertThat(preprocessor.findChanged()).isEmpty();
    }

    @Test
    void newFileAlongsideCommittedFile_onlyNewIsReturned() throws Exception {
        Path a = writeRaw("a.md", "alpha");
        preprocessor.commit(List.of(a));

        writeRaw("b.md", "beta");

        assertThat(preprocessor.findChanged())
                .extracting(p -> p.getFileName().toString())
                .containsExactly("b.md");
    }

    @Test
    void hiddenFilesAndManifest_areIgnored() throws Exception {
        writeRaw(".hidden", "secret");
        writeRaw(".compiled", "abc  phantom.md\n");
        writeRaw("real.md", "content");

        assertThat(preprocessor.findChanged())
                .extracting(p -> p.getFileName().toString())
                .containsExactly("real.md");
    }

    @Test
    void commit_emptyList_isNoop() throws Exception {
        preprocessor.commit(List.of());
        // Manifest should not have been created.
        assertThat(Files.exists(rawDir.resolve(".compiled"))).isFalse();
    }

    @Test
    void corruptedManifest_blankAndMalformedLines_areTolerated() throws Exception {
        Path a = writeRaw("a.md", "alpha");
        // Manually write a manifest with junk lines plus one valid entry for a.md.
        String validHash = "ea0be4f6ea38ee2d4f13a7a4f2c9a6cba4d54a8f20d7e37a3bda2bc68fa2b2a4"; // bogus
        String manifest = "\n"
                + "garbage line no double space\n"
                + validHash + "  a.md\n";
        Files.writeString(rawDir.resolve(".compiled"), manifest);

        // a.md is listed but hash is wrong → should still be reported as changed, not crash.
        List<Path> changed = preprocessor.findChanged();
        assertThat(changed).extracting(p -> p.getFileName().toString()).containsExactly("a.md");

        // After commit, it should be clean.
        preprocessor.commit(changed);
        assertThat(preprocessor.findChanged()).isEmpty();
    }

    @Test
    void subdirectories_areIgnored() throws Exception {
        Files.createDirectories(rawDir.resolve("sub"));
        Files.writeString(rawDir.resolve("sub/nested.md"), "nested");
        writeRaw("top.md", "top");

        assertThat(preprocessor.findChanged())
                .extracting(p -> p.getFileName().toString())
                .containsExactly("top.md");
    }
}
