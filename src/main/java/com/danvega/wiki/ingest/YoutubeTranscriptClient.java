package com.danvega.wiki.ingest;

import com.danvega.wiki.config.YoutubeProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
@ConditionalOnProperty(prefix = "wiki.youtube", name = "directus-token")
public class YoutubeTranscriptClient {

    private static final Logger log = LoggerFactory.getLogger(YoutubeTranscriptClient.class);
    private static final Pattern VIDEO_ID_PATTERN =
            Pattern.compile("(?:v=|youtu\\.be/)([a-zA-Z0-9_-]{11})");

    public record VideoRecord(
            String videoId,
            String title,
            String transcript,
            int durationSeconds,
            String uploadedAt
    ) {}

    private final YoutubeProperties props;
    private final RestClient fetcherClient;
    private final RestClient directusClient;
    private final ObjectMapper mapper = new ObjectMapper();

    public YoutubeTranscriptClient(YoutubeProperties props, RestClient.Builder restClientBuilder) {
        this.props = props;
        this.fetcherClient = restClientBuilder.baseUrl(props.fetcherUrl()).build();
        this.directusClient = restClientBuilder.clone()
                .baseUrl(props.directusUrl())
                .defaultHeader("Authorization", "Bearer " + props.directusToken())
                .build();
    }

    public VideoRecord fetchTranscript(String videoUrl) throws Exception {
        String videoId = extractVideoId(videoUrl);
        log.info("Fetching YouTube transcript for video_id={}", videoId);

        fetcherClient.post()
                .uri("/fetch-video")
                .body(Map.of("url", videoUrl))
                .retrieve()
                .toBodilessEntity();

        long deadline = System.currentTimeMillis() + props.timeoutSeconds() * 1000L;
        while (System.currentTimeMillis() < deadline) {
            Thread.sleep(props.pollIntervalSeconds() * 1000L);
            VideoRecord record = queryDirectus(videoId);
            if (record == null) continue;
            switch (record.transcript()) {
                case String t when !t.isBlank() -> {
                    log.info("Transcript ready for video_id={}, title=\"{}\"", videoId, record.title());
                    return record;
                }
                default -> {}
            }
            // Re-query status to detect terminal non-transcript states
            String status = queryStatus(videoId);
            if ("no_transcript".equals(status)) throw new IllegalStateException("No transcript available for " + videoId);
            if ("error".equals(status)) throw new RuntimeException("Transcript fetch failed for " + videoId);
        }
        throw new java.util.concurrent.TimeoutException(
                "Transcript not ready after %ds for video_id=%s".formatted(props.timeoutSeconds(), videoId));
    }

    private String extractVideoId(String url) {
        Matcher m = VIDEO_ID_PATTERN.matcher(url);
        if (!m.find()) throw new IllegalArgumentException("Cannot extract video_id from URL: " + url);
        return m.group(1);
    }

    private VideoRecord queryDirectus(String videoId) throws Exception {
        String json = directusClient.get()
                .uri("/items/videos?filter[video_id][_eq]={id}&limit=1", videoId)
                .retrieve()
                .body(String.class);
        if (json == null) return null;
        JsonNode data = mapper.readTree(json).path("data");
        if (!data.isArray() || data.isEmpty()) return null;
        JsonNode v = data.get(0);
        String transcript = v.path("transcript").asText("");
        String status = v.path("status").asText("pending");
        if ("pending".equals(status) && transcript.isBlank()) return null;
        return new VideoRecord(
                videoId,
                v.path("title").asText(""),
                transcript,
                v.path("duration_seconds").asInt(0),
                v.path("uploaded_at").asText("")
        );
    }

    private String queryStatus(String videoId) throws Exception {
        String json = directusClient.get()
                .uri("/items/videos?filter[video_id][_eq]={id}&fields=status&limit=1", videoId)
                .retrieve()
                .body(String.class);
        if (json == null) return "pending";
        JsonNode data = mapper.readTree(json).path("data");
        if (!data.isArray() || data.isEmpty()) return "pending";
        return data.get(0).path("status").asText("pending");
    }
}
