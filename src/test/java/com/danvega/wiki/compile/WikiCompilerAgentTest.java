package com.danvega.wiki.compile;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class WikiCompilerAgentTest {

    @Test
    void buildUserMessage_singleFile() {
        String msg = WikiCompilerAgent.buildUserMessage(List.of(Path.of("raw/foo.md")));

        assertThat(msg).contains("- raw/foo.md");
        assertThat(msg).contains("new or have changed");
    }

    @Test
    void buildUserMessage_multipleFiles() {
        String msg = WikiCompilerAgent.buildUserMessage(List.of(
                Path.of("raw/a.md"),
                Path.of("raw/b.md"),
                Path.of("raw/c.md")));

        int a = msg.indexOf("- raw/a.md");
        int b = msg.indexOf("- raw/b.md");
        int c = msg.indexOf("- raw/c.md");
        assertThat(a).isPositive();
        assertThat(a).isLessThan(b);
        assertThat(b).isLessThan(c);
    }

    @Test
    void buildUserMessage_stripsParentDirs() {
        String msg = WikiCompilerAgent.buildUserMessage(List.of(Path.of("raw/sub/foo.md")));

        assertThat(msg).contains("- raw/foo.md");
        assertThat(msg).doesNotContain("sub/foo.md");
    }
}
