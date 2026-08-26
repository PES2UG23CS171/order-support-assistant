package com.acme.ordersupport.guardrail;

import com.acme.ordersupport.config.AppProperties;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/**
 * Refuses input that is trying to talk to the system rather than to support.
 *
 * <p>Worth being clear about what this is and is not. It is a cheap, deterministic
 * first line that catches the obvious and the automated, and it costs nothing to
 * run. It is not a solution to prompt injection -- patterns are trivially evaded
 * by rephrasing, and anyone claiming otherwise is selling something. The actual
 * defence is structural and lives elsewhere: tools that take no identity
 * parameter, a service layer that authorizes every call, and state changes that
 * require a human to press a button. A prompt that gets past every pattern here
 * still cannot cancel an order.
 *
 * <p>What this buys is a clean refusal, an audit row, and no tokens spent on an
 * attack. In production it would sit in front of a model-based classifier rather
 * than instead of one.
 */
@Component
public class PromptInjectionGuard {

    public static final String NAME = "PROMPT_INJECTION";

    private static final String REPLY =
            "I can't act on that. I'm here to help with your orders, deliveries, returns and refunds -- "
            + "tell me what you need and I'll take a look.";

    private static final List<Pattern> PATTERNS = List.of(
            // Instruction override.
            compile("ignore\\s+(all\\s+|any\\s+)?(previous|prior|above|earlier)\\s+(instruction|prompt|rule)"),
            compile("disregard\\s+(all\\s+|any\\s+|the\\s+)?(previous|prior|above|earlier|system)"),
            compile("forget\\s+(everything|all)\\s+(you|above|before)"),
            compile("(new|updated)\\s+(system\\s+)?(instruction|prompt|rule)s?\\s*[:=]"),
            compile("you\\s+are\\s+now\\s+(a|an|the)\\b"),
            compile("(developer|debug|god|admin)\\s+mode"),
            compile("\\bjailbreak\\b|\\bDAN\\b"),
            // Prompt or configuration extraction.
            compile("(reveal|show|print|repeat|output|display)\\s+(me\\s+)?(your|the)\\s+"
                    + "(system\\s+)?(prompt|instruction|rule|configuration)"),
            compile("what\\s+(are|were)\\s+your\\s+(original\\s+)?(instruction|prompt|rule)"),
            compile("repeat\\s+(the\\s+)?(text|everything)\\s+above"),
            // Chat-template smuggling.
            compile("<\\|\\s*im_(start|end)\\s*\\|>"),
            compile("^\\s*#{1,3}\\s*(system|assistant)\\s*[:\\n]"),
            compile("\\[\\s*(system|assistant)\\s*\\]\\s*[:\\n]"),
            // Identity spoofing.
            compile("i\\s+am\\s+(the\\s+|an?\\s+)?(admin|administrator|developer|engineer|"
                    + "employee|support\\s+agent|staff|owner\\s+of\\s+this\\s+system)"),
            compile("(customer|account|user)[\\s_-]?id\\s*[:=]"),
            compile("(on\\s+behalf\\s+of|acting\\s+as)\\s+(customer|user|account)"),
            // Trying to talk past the confirmation gate.
            compile("(skip|bypass|ignore|without|no\\s+need\\s+for)\\s+(the\\s+)?(confirmation|approval|"
                    + "verification)"),
            compile("(don'?t|do\\s+not)\\s+ask\\s+(me\\s+)?(for\\s+)?(confirmation|approval|permission)"),
            compile("(cancel|refund)\\s+it\\s+(directly|immediately)\\s+without\\s+asking"));

    private final AppProperties properties;

    public PromptInjectionGuard(AppProperties properties) {
        this.properties = properties;
    }

    public GuardDecision inspect(String message) {
        if (!properties.guardrail().injectionCheckEnabled() || message == null || message.isBlank()) {
            return GuardDecision.allow();
        }
        String normalised = message.toLowerCase(Locale.ROOT);
        for (Pattern pattern : PATTERNS) {
            var matcher = pattern.matcher(normalised);
            if (matcher.find()) {
                return GuardDecision.block(NAME,
                        "Matched injection pattern /%s/ on \"%s\"".formatted(pattern.pattern(), matcher.group()),
                        REPLY);
            }
        }
        return GuardDecision.allow();
    }

    private static Pattern compile(String regex) {
        return Pattern.compile(regex, Pattern.CASE_INSENSITIVE | Pattern.MULTILINE);
    }
}
