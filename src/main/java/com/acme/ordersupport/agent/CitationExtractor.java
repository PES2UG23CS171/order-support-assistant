package com.acme.ordersupport.agent;

import com.acme.ordersupport.agent.model.Citation;
import com.acme.ordersupport.rag.RetrievedPolicy;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reads the {@code [n]} markers back out of the reply and resolves them to the
 * passages that were in the prompt.
 *
 * <p>This is what makes "the bot cites its source" a checkable claim rather than a
 * stylistic one. The markers are matched against the numbered passages that were
 * actually supplied, so a fabricated {@code [7]} against four passages resolves to
 * nothing and simply does not appear as a citation.
 */
public final class CitationExtractor {

    private static final Pattern MARKER = Pattern.compile("\\[(\\d{1,2})\\]");

    private CitationExtractor() {
    }

    public static List<Citation> citedIn(String reply, List<RetrievedPolicy> passages) {
        if (reply == null || passages.isEmpty()) {
            return List.of();
        }
        Set<Integer> markers = new LinkedHashSet<>();
        Matcher matcher = MARKER.matcher(reply);
        while (matcher.find()) {
            int marker = Integer.parseInt(matcher.group(1));
            if (marker >= 1 && marker <= passages.size()) {
                markers.add(marker);
            }
        }
        List<Citation> citations = new ArrayList<>();
        for (int marker : markers) {
            citations.add(toCitation(marker, passages.get(marker - 1)));
        }
        return citations;
    }

    public static List<Citation> asCitations(List<RetrievedPolicy> passages) {
        List<Citation> citations = new ArrayList<>();
        for (int i = 0; i < passages.size(); i++) {
            citations.add(toCitation(i + 1, passages.get(i)));
        }
        return citations;
    }

    private static Citation toCitation(int marker, RetrievedPolicy passage) {
        return new Citation(marker, passage.policyId(), passage.title(), passage.section(),
                passage.version(), passage.score(), excerpt(passage.text()));
    }

    private static String excerpt(String text) {
        String collapsed = text.replaceAll("\\s+", " ").strip();
        return collapsed.length() <= 320 ? collapsed : collapsed.substring(0, 317) + "...";
    }
}
