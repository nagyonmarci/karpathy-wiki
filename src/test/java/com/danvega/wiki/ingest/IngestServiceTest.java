package com.danvega.wiki.ingest;

import com.danvega.wiki.config.WikiProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.RestClient;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class IngestServiceTest {

    @TempDir
    Path tempDir;

    Path rawDir;
    RestClient.Builder restClientBuilder;
    MockRestServiceServer mockServer;
    ChatClient.Builder chatClientBuilder;
    ChatClient chatClient;
    IngestService ingestService;

    @BeforeEach
    void setUp() {
        rawDir = tempDir.resolve("raw");

        restClientBuilder = RestClient.builder();
        mockServer = MockRestServiceServer.bindTo(restClientBuilder).build();

        // Deep-stub the fluent ChatClient API: chatClient.prompt().user(...).call().content().
        chatClient = mock(ChatClient.class, RETURNS_DEEP_STUBS);
        chatClientBuilder = mock(ChatClient.Builder.class);
        when(chatClientBuilder.build()).thenReturn(chatClient);
        when(chatClient.prompt().user(anyString()).call().content())
                .thenReturn("# Converted markdown\n\nbody");

        WikiProperties props = new WikiProperties(
                new WikiProperties.Paths(rawDir.toString(), tempDir.resolve("wiki").toString(),
                        "skills", "memory"),
                new WikiProperties.Ingest(false));

        ingestService = new IngestService(restClientBuilder, chatClientBuilder, props);
    }

    @Test
    void ingestUrl_happyPath_writesFileWithFrontMatter() throws Exception {
        mockServer.expect(requestTo("https://example.com/post"))
                .andExpect(method(org.springframework.http.HttpMethod.GET))
                .andRespond(withSuccess("<html><body><h1>Hi</h1></body></html>", MediaType.TEXT_HTML));

        Path file = ingestService.ingestUrl("https://example.com/post", "Hello World", List.of("greet", "test"));

        mockServer.verify();
        assertThat(file).exists();
        String content = Files.readString(file);
        assertThat(content).startsWith("---\n");
        assertThat(content).contains("title: Hello World");
        assertThat(content).contains("source: https://example.com/post");
        assertThat(content).contains("ingested: " + LocalDate.now());
        assertThat(content).contains("tags: [greet, test]");
        assertThat(content).contains("# Converted markdown");
        assertThat(file.getFileName().toString())
                .isEqualTo(LocalDate.now() + "-hello-world.md");
    }

    @Test
    void ingestUrl_nullTitle_slugFromUrl() throws Exception {
        mockServer.expect(requestTo("https://example.com/some/path"))
                .andRespond(withSuccess("<html/>", MediaType.TEXT_HTML));

        Path file = ingestService.ingestUrl("https://example.com/some/path", null, null);

        // Slug strips https:// and replaces non-alphanumerics with dashes.
        assertThat(file.getFileName().toString())
                .isEqualTo(LocalDate.now() + "-example-com-some-path.md");
        String content = Files.readString(file);
        assertThat(content).doesNotContain("title:");
        assertThat(content).doesNotContain("tags:");
    }

    @Test
    void ingestUrl_blankTitle_fallsBackToUrl() throws Exception {
        mockServer.expect(requestTo("https://example.com/x")).andRespond(withSuccess("<html/>", MediaType.TEXT_HTML));

        Path file = ingestService.ingestUrl("https://example.com/x", "   ", List.of());

        assertThat(file.getFileName().toString()).contains("example-com-x");
    }

    @Test
    void ingestUrl_longTitle_slugTruncatedTo80Chars() throws Exception {
        mockServer.expect(requestTo("https://example.com")).andRespond(withSuccess("<html/>", MediaType.TEXT_HTML));

        String veryLong = "a".repeat(200);
        Path file = ingestService.ingestUrl("https://example.com", veryLong, null);

        // Filename = "<date>-<slug>.md"; slug should be exactly 80 chars.
        String name = file.getFileName().toString();
        String slug = name.substring((LocalDate.now() + "-").length(), name.length() - ".md".length());
        assertThat(slug).hasSize(80);
    }

    @Test
    void ingestUrl_unicodeAndPunctuation_strippedFromSlug() throws Exception {
        mockServer.expect(requestTo("https://example.com")).andRespond(withSuccess("<html/>", MediaType.TEXT_HTML));

        Path file = ingestService.ingestUrl("https://example.com", "Héllo, World!! ☕", null);

        String slug = file.getFileName().toString();
        // Non-ASCII chars become dashes; verify only the safe alphabet remains in the slug portion.
        assertThat(slug).matches(LocalDate.now() + "-[a-z0-9-]+\\.md");
    }

    @Test
    void ingestUrl_404_throws_andNoFileWritten() {
        mockServer.expect(requestTo("https://example.com/missing"))
                .andRespond(withStatus(org.springframework.http.HttpStatus.NOT_FOUND));

        assertThatThrownBy(() -> ingestService.ingestUrl("https://example.com/missing", "x", null))
                .isInstanceOf(HttpClientErrorException.NotFound.class);

        assertThat(rawDir).satisfiesAnyOf(
                p -> assertThat(p).doesNotExist(),
                p -> {
                    try (Stream<Path> s = Files.list(p)) {
                        assertThat(s).isEmpty();
                    }
                });
    }

    @Test
    void ingestUrl_500_throws() {
        mockServer.expect(requestTo("https://example.com/boom"))
                .andRespond(withServerError());

        assertThatThrownBy(() -> ingestService.ingestUrl("https://example.com/boom", "x", null))
                .isInstanceOf(HttpServerErrorException.class);
    }

    @Test
    void ingestUrl_chatClientFailure_throws_andNoFileWritten() {
        mockServer.expect(requestTo("https://example.com/ok"))
                .andRespond(withSuccess("<html/>", MediaType.TEXT_HTML));
        when(chatClient.prompt().user(anyString()).call().content())
                .thenThrow(new RuntimeException("LLM down"));

        assertThatThrownBy(() -> ingestService.ingestUrl("https://example.com/ok", "x", null))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("LLM down");

        assertThat(rawDir).satisfiesAnyOf(
                p -> assertThat(p).doesNotExist(),
                p -> {
                    try (Stream<Path> s = Files.list(p)) {
                        assertThat(s).isEmpty();
                    }
                });
    }
}
