package com.acme.ordersupport.guardrail;

import com.acme.ordersupport.config.AppProperties;
import com.acme.ordersupport.rag.RetrievedPolicy;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/**
 * Decides whether a message is this assistant's problem at all.
 *
 * <p>Runs after retrieval and before the model, so an out-of-scope question costs
 * one vector search and no completion. Four signals put a message in scope: an
 * order number, a domain word, an ordinary greeting, or a policy passage that
 * matched well enough to be worth grounding on. An explicit off-topic pattern --
 * write me code, what's the weather, who should I vote for -- overrides all of
 * them, because "what's the return on this stock" contains a domain word and is
 * still not a question about returns.
 *
 * <p>Being deterministic is the feature: a refusal is reproducible, greppable in
 * the guardrail log, and cannot be argued with by the next message.
 */
@Component
public class ScopeGuard {

    public static final String NAME = "OUT_OF_SCOPE";

    private static final String REPLY =
            "I can only help with orders, deliveries, returns, refunds and shipping for this store. "
            + "If you have an order number I'm happy to take a look at it.";

    private static final Pattern ORDER_NUMBER = Pattern.compile("\\b(ord|rma|rfnd)[-\\s]?\\d{3,}\\b",
            Pattern.CASE_INSENSITIVE);

    private static final Set<String> DOMAIN_WORDS = Set.of(
            "order", "orders", "ordered", "delivery", "deliver", "delivered", "delivering", "parcel",
            "package", "shipment", "shipping", "shipped", "ship", "tracking", "track", "courier",
            "carrier", "return", "returns", "returning", "refund", "refunds", "refunded", "rma",
            "exchange", "cancel", "cancels", "cancelled", "cancellation", "money", "charge", "charged",
            "payment", "paid", "invoice", "receipt", "item", "items", "product", "purchase", "bought",
            "warranty", "damaged", "broken", "defective", "missing", "lost", "late", "delayed",
            "arrive", "arrived", "arriving", "eta", "label", "restocking", "policy");

    private static final Pattern GREETING = Pattern.compile(
            "^\\s*(hi|hey|hello|good\\s+(morning|afternoon|evening)|thanks|thank\\s+you|ta|cheers|"
            + "ok|okay|yes|no|please|sorry|bye|goodbye)\\b[\\s\\S]{0,40}$",
            Pattern.CASE_INSENSITIVE);

    private static final List<Pattern> OFF_TOPIC = List.of(
            compile("\\b(write|generate|debug|refactor|explain)\\s+(me\\s+)?(some\\s+)?"
                    + "(code|a\\s+function|a\\s+script|python|java|sql|javascript)"),
            compile("\\bweather\\b|\\bforecast\\b"),
            compile("\\b(who|which\\s+party)\\s+should\\s+i\\s+vote"),
            compile("\\b(stock|shares|crypto|bitcoin|invest|portfolio)\\b.*\\b(buy|sell|advice|recommend)"),
            compile("\\b(buy|sell|invest|investing)\\b.*\\b(stock|shares|crypto|bitcoin|portfolio)\\b"),
            compile("\\b(diagnos|symptom|prescri|medication|dosage)"),
            compile("\\b(recipe|cook|bake)\\b"),
            compile("\\b(write|compose)\\s+(me\\s+)?(a|an)\\s+(poem|song|essay|story|email\\s+to\\s+my)"),
            compile("\\btranslate\\b.*\\b(into|to)\\s+\\w+"),
            compile("\\b(homework|assignment|exam\\s+question)\\b"),
            compile("\\bwhat\\s+model\\s+(are\\s+you|do\\s+you\\s+use)\\b|\\bare\\s+you\\s+(chatgpt|gpt|claude)"));

    private final AppProperties properties;

    public ScopeGuard(AppProperties properties) {
        this.properties = properties;
    }

    public GuardDecision assess(String message, List<RetrievedPolicy> passages) {
        return assess(message, passages, false);
    }

    /**
     * @param continuingConversation whether this customer has already had a turn in
     *                               this conversation that was not blocked
     */
    public GuardDecision assess(String message, List<RetrievedPolicy> passages,
                                boolean continuingConversation) {
        if (!properties.guardrail().scopeCheckEnabled() || message == null || message.isBlank()) {
            return GuardDecision.allow();
        }

        String normalised = message.toLowerCase(Locale.ROOT);

        for (Pattern pattern : OFF_TOPIC) {
            var matcher = pattern.matcher(normalised);
            if (matcher.find()) {
                return GuardDecision.block(NAME,
                        "Matched off-topic pattern /%s/ on \"%s\""
                                .formatted(pattern.pattern(), matcher.group()), REPLY);
            }
        }

        if (ORDER_NUMBER.matcher(normalised).find()) {
            return GuardDecision.allow();
        }
        if (GREETING.matcher(message.strip()).matches()) {
            return GuardDecision.allow();
        }
        for (String word : normalised.split("[^a-z0-9]+")) {
            if (DOMAIN_WORDS.contains(word)) {
                return GuardDecision.allow();
            }
        }
        if (!passages.isEmpty()) {
            return GuardDecision.allow();
        }

        // "and when will it get here?" carries no order number, no domain word and
        // matches no passage -- read alone it looks out of scope, and refusing it
        // mid-conversation is absurd. Follow-up turns get the benefit of the doubt;
        // the explicit off-topic patterns above still apply, so a hard turn onto
        // the weather is still refused however deep into a conversation it comes.
        if (continuingConversation) {
            return GuardDecision.allow();
        }

        return GuardDecision.block(NAME,
                "No order reference, no domain vocabulary and no policy passage matched", REPLY);
    }

    private static Pattern compile(String regex) {
        return Pattern.compile(regex, Pattern.CASE_INSENSITIVE);
    }
}
