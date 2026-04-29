package com.danvega.wiki.ingest;

import com.danvega.wiki.config.WikiProperties;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

@Service
public class IngestService {

    private static final Logger log = LoggerFactory.getLogger(IngestService.class);

    private final RestClient restClient;
    private final ChatClient chatClient;
    private final WikiProperties props;
    private final Optional<YoutubeTranscriptClient> youtubeClient;

    public IngestService(RestClient.Builder restClientBuilder,
                         ChatClient.Builder chatClientBuilder,
                         WikiProperties props,
                         Optional<YoutubeTranscriptClient> youtubeClient) {
        this.restClient = restClientBuilder.build();
        this.chatClient = chatClientBuilder.build();
        this.props = props;
        this.youtubeClient = youtubeClient;
    }

    public Path ingestUrl(String url, String title, List<String> tags) throws Exception {
        if (isYoutubeUrl(url)) {
            if (youtubeClient.isPresent()) {
                return ingestYoutube(url, title, tags);
            }
            log.warn("YouTube URL detected but YOUTUBE_DIRECTUS_TOKEN is not configured — falling back to HTML scraping");
        }
        return ingestHtml(url, title, tags);
    }

    private Path ingestYoutube(String url, String title, List<String> tags) throws Exception {
        YoutubeTranscriptClient.VideoRecord video = youtubeClient.get().fetchTranscript(url);

        String effectiveTitle = (title != null && !title.isBlank()) ? title : video.title();
        String prompt = """
                The following is a YouTube video transcript. Convert it into a clean Markdown wiki note.
                Structure it with logical headings, preserve code examples, and extract key insights.
                Add YAML frontmatter with title, source URL, and tags.
                Output only the markdown file content.

                Title: %s
                Source: %s
                Duration: %ds
                Uploaded: %s

                Transcript:
                %s
                """.formatted(effectiveTitle, url, video.durationSeconds(), video.uploadedAt(), video.transcript());

        String markdown = chatClient.prompt().user(prompt).call().content();

        String slug = slugify(effectiveTitle);
        Path rawDir = Path.of(props.paths().raw());
        Files.createDirectories(rawDir);
        Path file = rawDir.resolve(LocalDate.now() + "-" + slug + ".md");

        StringBuilder out = new StringBuilder();
        out.append("---\n");
        out.append("title: ").append(effectiveTitle).append('\n');
        out.append("source: ").append(url).append('\n');
        out.append("ingested: ").append(LocalDate.now()).append('\n');
        if (!video.uploadedAt().isBlank()) out.append("uploaded: ").append(video.uploadedAt()).append('\n');
        if (tags != null && !tags.isEmpty()) out.append("tags: [").append(String.join(", ", tags)).append("]\n");
        out.append("---\n\n");
        out.append(markdown);

        Files.writeString(file, out.toString());
        log.info("Saved YouTube note to {}", file);
        return file;
    }

    private Path ingestHtml(String url, String title, List<String> tags) throws Exception {
        log.info("Ingesting URL: {}", url);

        String html = restClient.get()
                .uri(url)
                .retrieve()
                .body(String.class);

        Document doc = Jsoup.parse(html, url);
        doc.select(
                "script, style, noscript, iframe, svg, link, meta, " +
                "nav, header, footer, aside, form, button, " +
                "[role=navigation], [role=banner], [role=contentinfo], " +
                ".nav, .navigation, .menu, .sidebar, .footer, .header, " +
                ".comments, .related, .share, .social, .ads, .advertisement"
        ).remove();

        Element main = doc.selectFirst(
                "article, main, [role=main], .post, .post-content, .entry-content, .article-content"
        );
        Element root = main != null ? main : doc.body();
        String cleaned = root.html();
        log.info("Extracted {} chars of content from {} chars of HTML", cleaned.length(), html.length());

        String prompt = """
                Convert the following HTML fragment into clean, readable Markdown.
                - Preserve headings, code blocks, lists, tables, and meaningful links.
                - Output Markdown only — no commentary.

                Source URL: %s

                HTML:
                %s
                """.formatted(url, cleaned);

        String markdown = chatClient.prompt().user(prompt).call().content();

        String slug = slugify(title != null && !title.isBlank() ? title : url);
        Path rawDir = Path.of(props.paths().raw());
        Files.createDirectories(rawDir);
        Path file = rawDir.resolve(LocalDate.now() + "-" + slug + ".md");

        StringBuilder out = new StringBuilder();
        out.append("---\n");
        if (title != null) out.append("title: ").append(title).append('\n');
        out.append("source: ").append(url).append('\n');
        out.append("ingested: ").append(LocalDate.now()).append('\n');
        if (tags != null && !tags.isEmpty()) {
            out.append("tags: [").append(String.join(", ", tags)).append("]\n");
        }
        out.append("---\n\n");
        out.append(markdown);

        Files.writeString(file, out.toString());
        log.info("Saved ingested content to {}", file);
        return file;
    }

    private static boolean isYoutubeUrl(String url) {
        return url.contains("youtube.com/watch") || url.contains("youtu.be/");
    }

    private static String slugify(String input) {
        String s = input.toLowerCase(Locale.ROOT)
                .replaceAll("https?://", "")
                .replaceAll("[^a-z0-9]+", "-")
                .replaceAll("(^-|-$)", "");
        return s.length() > 80 ? s.substring(0, 80) : s;
    }
}
