package com.danvega.wiki.compile;

import com.danvega.wiki.config.WikiProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * Tracks which files in {@code raw/} have already been compiled into the wiki.
 *
 * <p>Processed-state lives in {@code raw/.compiled} as a
 * {@code filename -> sha256} map. Files are <em>not</em> moved out of
 * {@code raw/} after a compile; instead the manifest is updated. Re-running
 * {@code compile} only re-feeds files whose hash has changed (or new ones).</p>
 */
@Component
public class RawPreprocessor {

    private static final Logger log = LoggerFactory.getLogger(RawPreprocessor.class);
    private static final String MANIFEST_NAME = ".compiled";

    private final WikiProperties props;

    public RawPreprocessor(WikiProperties props) {
        this.props = props;
    }

    /**
     * Return regular files in {@code raw/} whose content hash differs from
     * the manifest (i.e. new or modified since last compile). Hidden files
     * and the manifest itself are excluded.
     */
    public List<Path> findChanged() throws Exception {
        Path rawDir = Path.of(props.paths().raw());
        if (!Files.exists(rawDir)) return List.of();

        Map<String, String> manifest = readManifest(rawDir);
        List<Path> changed = new ArrayList<>();

        try (Stream<Path> files = Files.list(rawDir)) {
            for (Path file : (Iterable<Path>) files::iterator) {
                if (!Files.isRegularFile(file)) continue;
                String name = file.getFileName().toString();
                if (name.startsWith(".")) continue;
                String hash = sha256(file);
                if (!hash.equals(manifest.get(name))) {
                    changed.add(file);
                }
            }
        }
        return changed;
    }

    /** Update the manifest with the current hashes of the given files. */
    public void commit(List<Path> files) throws Exception {
        if (files.isEmpty()) return;
        Path rawDir = Path.of(props.paths().raw());
        Map<String, String> manifest = new LinkedHashMap<>(readManifest(rawDir));
        for (Path file : files) {
            if (Files.isRegularFile(file)) {
                manifest.put(file.getFileName().toString(), sha256(file));
            }
        }
        writeManifest(rawDir, manifest);
        log.info("Manifest updated for {} file(s)", files.size());
    }

    // --- internals -----------------------------------------------------------

    /** Manifest format: one {@code <sha256>  <filename>} per line. */
    private Map<String, String> readManifest(Path rawDir) throws Exception {
        Path manifest = rawDir.resolve(MANIFEST_NAME);
        if (!Files.exists(manifest)) return Map.of();
        Map<String, String> map = new LinkedHashMap<>();
        for (String line : Files.readAllLines(manifest)) {
            if (line.isBlank()) continue;
            int sp = line.indexOf("  ");
            if (sp > 0) map.put(line.substring(sp + 2), line.substring(0, sp));
        }
        return map;
    }

    private void writeManifest(Path rawDir, Map<String, String> manifest) throws Exception {
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, String> e : manifest.entrySet()) {
            sb.append(e.getValue()).append("  ").append(e.getKey()).append('\n');
        }
        Files.writeString(rawDir.resolve(MANIFEST_NAME), sb.toString());
    }

    private static String sha256(Path file) throws Exception {
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        return HexFormat.of().formatHex(md.digest(Files.readAllBytes(file)));
    }
}
