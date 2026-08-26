package com.acme.ordersupport.rag;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.Embedding;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.embedding.EmbeddingRequest;
import org.springframework.ai.embedding.EmbeddingResponse;

/**
 * A deterministic, offline embedding model: signed feature hashing over a
 * TF-weighted bag of words, L2-normalised so a dot product is a cosine.
 *
 * <p>It exists so retrieval can be tested and demonstrated for real -- against
 * actual pgvector, actual HNSW cosine search, actual ranking -- with no API key,
 * no network call, and no per-run cost. It is not a semantic model: it matches on
 * shared vocabulary, not on meaning, so "how long do I have to send something
 * back" will not find the returns section unless the words overlap. That is the
 * honest trade, and it is the right one for CI, where a flaky or billable
 * assertion is worse than a narrow one.
 *
 * <p>Production runs with {@code app.ai.mode=openai} and never constructs this.
 *
 * <p>Output dimension matches text-embedding-3-small so a single {@code
 * vector(1536)} column serves both modes and no migration is needed to switch.
 */
public class HashingEmbeddingModel implements EmbeddingModel {

    public static final int DIMENSIONS = 1536;

    private static final Pattern TOKEN = Pattern.compile("[a-z0-9]+");

    /** Words carried by almost every support question; they only add noise to the vector. */
    private static final Set<String> STOP_WORDS = Set.of(
            "a", "an", "and", "any", "are", "as", "at", "be", "been", "but", "by", "can", "do", "does",
            "for", "from", "get", "has", "have", "how", "i", "if", "in", "is", "it", "its", "me", "my",
            "not", "of", "on", "or", "so", "that", "the", "their", "them", "then", "there", "they",
            "this", "to", "was", "we", "were", "what", "when", "where", "which", "who", "will", "with",
            "would", "you", "your");

    @Override
    public EmbeddingResponse call(EmbeddingRequest request) {
        List<Embedding> embeddings = new ArrayList<>();
        List<String> instructions = request.getInstructions();
        for (int i = 0; i < instructions.size(); i++) {
            embeddings.add(new Embedding(embed(instructions.get(i)), i));
        }
        return new EmbeddingResponse(embeddings);
    }

    @Override
    public float[] embed(Document document) {
        return embed(document.getText());
    }

    @Override
    public float[] embed(String text) {
        float[] vector = new float[DIMENSIONS];
        if (text == null || text.isBlank()) {
            return vector;
        }

        var matcher = TOKEN.matcher(text.toLowerCase(Locale.ROOT));
        while (matcher.find()) {
            String token = stem(matcher.group());
            if (token.length() < 2 || STOP_WORDS.contains(token)) {
                continue;
            }
            int hash = token.hashCode();
            int bucket = Math.floorMod(hash, DIMENSIONS);
            // A second, independent bit decides the sign. Signed hashing makes
            // collisions cancel out on average instead of always reinforcing.
            int sign = ((hash >>> 16) & 1) == 0 ? 1 : -1;
            vector[bucket] += sign;
        }

        // Sublinear term frequency: a word repeated ten times should not outweigh
        // ten distinct words, which is what raw counts would do to a long section.
        double norm = 0;
        for (int i = 0; i < DIMENSIONS; i++) {
            if (vector[i] != 0) {
                float magnitude = (float) (1 + Math.log(Math.abs(vector[i])));
                vector[i] = Math.signum(vector[i]) * magnitude;
                norm += (double) vector[i] * vector[i];
            }
        }

        if (norm > 0) {
            float inverse = (float) (1 / Math.sqrt(norm));
            for (int i = 0; i < DIMENSIONS; i++) {
                vector[i] *= inverse;
            }
        }
        return vector;
    }

    /** Crude plural stripping so "refunds" and "refund" land in the same bucket. */
    private static String stem(String token) {
        if (token.length() > 3 && token.endsWith("ies")) {
            return token.substring(0, token.length() - 3) + "y";
        }
        if (token.length() > 3 && token.endsWith("es") && !token.endsWith("ses")) {
            return token.substring(0, token.length() - 2);
        }
        if (token.length() > 3 && token.endsWith("s") && !token.endsWith("ss")) {
            return token.substring(0, token.length() - 1);
        }
        return token;
    }

    @Override
    public int dimensions() {
        return DIMENSIONS;
    }
}
