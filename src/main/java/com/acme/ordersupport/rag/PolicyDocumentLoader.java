package com.acme.ordersupport.rag;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.stereotype.Component;

/**
 * Turns the markdown corpus into chunks.
 *
 * <p>Sections are the chunk boundary rather than a fixed token count, because the
 * documents were written one-question-per-heading. A chunk that begins mid-rule is
 * a chunk that cites badly: the customer gets a fragment with no subject. Sections
 * longer than the configured limit fall back to paragraph splitting with overlap,
 * so no chunk is unbounded.
 */
@Component
public class PolicyDocumentLoader {

    private static final String LOCATION = "classpath:policies/*.md";
    private static final String FRONT_MATTER_DELIMITER = "---";

    public List<PolicyChunk> load(int maxChunkChars, int overlapChars) {
        List<PolicyChunk> chunks = new ArrayList<>();
        for (Resource resource : findResources()) {
            chunks.addAll(parse(resource, maxChunkChars, overlapChars));
        }
        return chunks;
    }

    private Resource[] findResources() {
        try {
            return new PathMatchingResourcePatternResolver().getResources(LOCATION);
        }
        catch (IOException ex) {
            throw new UncheckedIOException("Unable to read the policy corpus at " + LOCATION, ex);
        }
    }

    List<PolicyChunk> parse(Resource resource, int maxChunkChars, int overlapChars) {
        String raw = read(resource);
        Map<String, String> frontMatter = new HashMap<>();
        String body = raw;

        if (raw.startsWith(FRONT_MATTER_DELIMITER)) {
            int end = raw.indexOf("\n" + FRONT_MATTER_DELIMITER, FRONT_MATTER_DELIMITER.length());
            if (end > 0) {
                for (String line : raw.substring(FRONT_MATTER_DELIMITER.length(), end).split("\n")) {
                    int colon = line.indexOf(':');
                    if (colon > 0) {
                        frontMatter.put(line.substring(0, colon).trim(), line.substring(colon + 1).trim());
                    }
                }
                body = raw.substring(end + FRONT_MATTER_DELIMITER.length() + 1);
            }
        }

        String source = resource.getFilename() != null ? resource.getFilename() : "unknown.md";
        String policyId = frontMatter.getOrDefault("policy_id", source.replace(".md", ""));
        String title = frontMatter.getOrDefault("title", policyId);
        String version = frontMatter.getOrDefault("version", "unversioned");
        String docHash = sha256Hex(raw);

        List<PolicyChunk> chunks = new ArrayList<>();
        int index = 0;
        for (Map.Entry<String, String> section : splitSections(body).entrySet()) {
            for (String piece : splitLongSection(section.getValue(), maxChunkChars, overlapChars)) {
                String text = "%s -- %s\n\n%s".formatted(title, section.getKey(), piece.strip());
                chunks.add(new PolicyChunk(
                        deterministicId(policyId, section.getKey(), index),
                        policyId, title, section.getKey(), version, source, index, docHash, text));
                index++;
            }
        }
        return chunks;
    }

    /** Splits on level-two headings, preserving order. */
    private static Map<String, String> splitSections(String body) {
        Map<String, String> sections = new java.util.LinkedHashMap<>();
        String currentHeading = "Overview";
        StringBuilder buffer = new StringBuilder();
        for (String line : body.split("\n", -1)) {
            if (line.startsWith("## ")) {
                if (!buffer.toString().isBlank()) {
                    sections.put(currentHeading, buffer.toString());
                }
                currentHeading = line.substring(3).strip();
                buffer = new StringBuilder();
            }
            else {
                buffer.append(line).append('\n');
            }
        }
        if (!buffer.toString().isBlank()) {
            sections.put(currentHeading, buffer.toString());
        }
        return sections;
    }

    /** Paragraph-aligned fallback with overlap, used only when a section runs long. */
    static List<String> splitLongSection(String section, int maxChunkChars, int overlapChars) {
        String text = section.strip();
        if (text.length() <= maxChunkChars) {
            return List.of(text);
        }

        List<String> pieces = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        for (String paragraph : text.split("\n\n")) {
            if (current.length() + paragraph.length() > maxChunkChars && !current.isEmpty()) {
                pieces.add(current.toString().strip());
                String previous = current.toString();
                current = new StringBuilder(
                        previous.substring(Math.max(0, previous.length() - overlapChars)));
            }
            current.append(paragraph).append("\n\n");
        }
        if (!current.toString().isBlank()) {
            pieces.add(current.toString().strip());
        }
        return pieces;
    }

    /**
     * A stable UUID per (policy, section, index). PgVectorStore inserts with
     * {@code ON CONFLICT (id) DO UPDATE}, so re-ingestion after an edit overwrites
     * the chunk in place instead of leaving a stale copy alongside the new one.
     */
    static String deterministicId(String policyId, String section, int index) {
        String key = policyId + '#' + section.toLowerCase(Locale.ROOT) + '#' + index;
        return UUID.nameUUIDFromBytes(key.getBytes(StandardCharsets.UTF_8)).toString();
    }

    private static String read(Resource resource) {
        try (var stream = resource.getInputStream()) {
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }
        catch (IOException ex) {
            throw new UncheckedIOException("Unable to read policy document " + resource.getFilename(), ex);
        }
    }

    static String sha256Hex(String value) {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
        }
        catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 is required by every JRE", ex);
        }
    }
}
