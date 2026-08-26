package com.acme.ordersupport.agent;

import com.acme.ordersupport.agent.audit.AuditService;
import com.acme.ordersupport.agent.audit.ToolInvocation;
import com.acme.ordersupport.agent.confirm.ConfirmationService;
import com.acme.ordersupport.agent.confirm.PendingAction;
import com.acme.ordersupport.agent.confirm.PendingActionStatus;
import com.acme.ordersupport.agent.model.ChatTurn;
import com.acme.ordersupport.agent.model.Citation;
import com.acme.ordersupport.agent.model.PendingActionSummary;
import com.acme.ordersupport.agent.model.ToolCallSummary;
import com.acme.ordersupport.config.AppProperties;
import com.acme.ordersupport.guardrail.GuardDecision;
import com.acme.ordersupport.guardrail.GuardrailEventRepository;
import com.acme.ordersupport.guardrail.PromptInjectionGuard;
import com.acme.ordersupport.guardrail.ScopeGuard;
import com.acme.ordersupport.rag.PolicyRetriever;
import com.acme.ordersupport.rag.RetrievedPolicy;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Service;

/**
 * One chat turn, start to finish.
 *
 * <p>The order of the steps is the design:
 *
 * <pre>
 *   authorize conversation -> injection guard -> retrieve -> scope guard
 *       -> model + tools -> extract citations -> read back the audit trail
 * </pre>
 *
 * <p>Both guards run before the model, so a blocked message costs a vector search
 * at most and never becomes a completion. Retrieval happens before the scope guard
 * because a good policy match is itself evidence that a question is in scope --
 * cheaper than a classifier and it reuses work the turn needs anyway.
 */
@Service
public class SupportAgent {

    private static final Logger log = LoggerFactory.getLogger(SupportAgent.class);

    private final ChatClient chatClient;
    private final SupportTools tools;
    private final PolicyRetriever retriever;
    private final PromptInjectionGuard injectionGuard;
    private final ScopeGuard scopeGuard;
    private final GuardrailEventRepository guardrailEvents;
    private final ConversationRepository conversations;
    private final ConfirmationService confirmations;
    private final AuditService audit;
    private final AppProperties properties;
    private final String systemPromptTemplate;

    public SupportAgent(ChatClient chatClient, SupportTools tools, PolicyRetriever retriever,
                        PromptInjectionGuard injectionGuard, ScopeGuard scopeGuard,
                        GuardrailEventRepository guardrailEvents, ConversationRepository conversations,
                        ConfirmationService confirmations, AuditService audit, AppProperties properties,
                        @org.springframework.beans.factory.annotation.Value("classpath:prompts/system-prompt.md")
                        Resource systemPrompt) {
        this.chatClient = chatClient;
        this.tools = tools;
        this.retriever = retriever;
        this.injectionGuard = injectionGuard;
        this.scopeGuard = scopeGuard;
        this.guardrailEvents = guardrailEvents;
        this.conversations = conversations;
        this.confirmations = confirmations;
        this.audit = audit;
        this.properties = properties;
        this.systemPromptTemplate = read(systemPrompt);
    }

    public ChatTurn handle(UUID customerId, UUID requestedConversationId, String message) {
        UUID conversationId = resolveConversation(customerId, requestedConversationId);
        SupportSession session = new SupportSession(conversationId, customerId);

        // Read before appending this turn's message, so it does not count itself.
        boolean continuingConversation = !conversations.recentMessages(conversationId, 1).isEmpty();
        conversations.append(conversationId, ConversationRepository.StoredMessage.USER, message);
        long auditWatermark = audit.highWaterMark(conversationId);

        GuardDecision injection = injectionGuard.inspect(message);
        if (injection.blocked()) {
            return refuse(session, injection, message, List.of(), auditWatermark);
        }

        List<RetrievedPolicy> passages = retriever.retrieve(message);

        GuardDecision scope = scopeGuard.assess(message, passages, continuingConversation);
        if (scope.blocked()) {
            return refuse(session, scope, message, passages, auditWatermark);
        }

        String reply = callModel(session, message, passages);
        conversations.append(conversationId, ConversationRepository.StoredMessage.ASSISTANT, reply);

        return new ChatTurn(conversationId, reply,
                CitationExtractor.citedIn(reply, passages),
                CitationExtractor.asCitations(passages),
                toolCallsSince(conversationId, auditWatermark),
                latestPendingAction(conversationId),
                null, true);
    }

    private String callModel(SupportSession session, String message, List<RetrievedPolicy> passages) {
        List<Message> messages = new ArrayList<>();
        messages.add(new SystemMessage(systemPrompt(passages)));
        for (var stored : conversations.recentMessages(session.conversationId(),
                properties.agent().maxHistoryMessages())) {
            messages.add(ConversationRepository.StoredMessage.ASSISTANT.equals(stored.role())
                    ? new AssistantMessage(stored.content())
                    : new UserMessage(stored.content()));
        }

        // The turn's own message was appended to history a moment ago, so it is
        // already the last entry; adding it again would duplicate it.
        return chatClient.prompt()
                .messages(messages)
                .tools(tools)
                .toolContext(session.asToolContext())
                .call()
                .content();
    }

    /**
     * Builds the system message by appending the retrieved passages, numbered.
     *
     * <p>Assembled by concatenation rather than through a template engine on
     * purpose: policy text is arbitrary prose that will one day contain a brace or
     * a dollar sign, and a templating pass over untrusted-ish content is a failure
     * waiting for the wrong document edit.
     */
    String systemPrompt(List<RetrievedPolicy> passages) {
        return systemPromptTemplate
               + "\n\n## Policy passages\n\n"
               + PolicyRetriever.asContextBlock(passages)
               + "\n";
    }

    private ChatTurn refuse(SupportSession session, GuardDecision decision, String message,
                            List<RetrievedPolicy> passages, long auditWatermark) {
        guardrailEvents.record(session.conversationId(), session.customerId(), decision, message);
        conversations.append(session.conversationId(), ConversationRepository.StoredMessage.ASSISTANT,
                decision.reply());
        log.info("Guardrail {} blocked a message in conversation {}: {}",
                decision.guardrail(), session.conversationId(), decision.reason());
        return new ChatTurn(session.conversationId(), decision.reply(), List.of(),
                CitationExtractor.asCitations(passages),
                toolCallsSince(session.conversationId(), auditWatermark),
                null, decision.guardrail(), false);
    }

    /**
     * Verifies the caller owns the conversation before continuing it. Without this
     * a conversation id -- which travels in a request body and is trivially
     * guessable to anyone who has seen one -- would be enough to read someone
     * else's support history.
     */
    private UUID resolveConversation(UUID customerId, UUID requestedConversationId) {
        if (requestedConversationId == null) {
            return conversations.create(customerId);
        }
        UUID owner = conversations.ownerOf(requestedConversationId).orElseThrow(
                () -> new ConversationNotFoundException(requestedConversationId));
        if (!owner.equals(customerId)) {
            log.warn("Customer {} attempted to continue conversation {} owned by {}",
                    customerId, requestedConversationId, owner);
            throw new ConversationNotFoundException(requestedConversationId);
        }
        return requestedConversationId;
    }

    /**
     * The tools this turn used, read back out of the audit table.
     *
     * <p>Deliberately not accumulated in a list during the turn. Reporting from the
     * durable record means the response cannot claim a tool ran that was never
     * logged, or hide one that was.
     */
    private List<ToolCallSummary> toolCallsSince(UUID conversationId, long watermark) {
        return audit.since(conversationId, watermark).stream()
                .map(SupportAgent::toSummary)
                .toList();
    }

    private static ToolCallSummary toSummary(ToolInvocation invocation) {
        return new ToolCallSummary(invocation.toolName(), invocation.actor().name(),
                invocation.outcome().name(), invocation.detail(), invocation.durationMs(),
                invocation.createdAt());
    }

    private PendingActionSummary latestPendingAction(UUID conversationId) {
        return confirmations.forConversation(conversationId).stream()
                .filter(action -> action.status() == PendingActionStatus.AWAITING_CONFIRMATION)
                .max(Comparator.comparing(PendingAction::createdAt))
                .map(PendingActionSummary::from)
                .orElse(null);
    }

    /** Exposed for the audit endpoint and for tests that assert on grounding. */
    public List<Citation> retrievedFor(String query) {
        return CitationExtractor.asCitations(retriever.retrieve(query));
    }

    private static String read(Resource resource) {
        try (var stream = resource.getInputStream()) {
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8).strip();
        }
        catch (IOException ex) {
            throw new UncheckedIOException("Unable to read the system prompt", ex);
        }
    }
}
