package com.acme.ordersupport.testsupport;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.metadata.ChatGenerationMetadata;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.ai.model.tool.ToolExecutionResult;
import org.springframework.ai.tool.ToolCallback;

/**
 * A chat model whose replies the test writes, driving the framework's real tool
 * loop.
 *
 * <p>Calling a hosted model from a test suite buys nothing and costs a great deal:
 * the assertion becomes non-deterministic, the run becomes billable, and CI
 * becomes dependent on somebody else's uptime. What actually needs testing is the
 * machinery around the model -- schema generation, argument binding, the
 * authorization checks in the tools, the audit rows, the confirmation gate -- and
 * all of that is exercised by scripting the one decision a real model would make
 * and letting Spring AI's {@link ToolCallingManager} do everything else for real.
 *
 * <p>What it does not test is whether a real model chooses the right tool. That is
 * a prompt-evaluation question and belongs in an eval suite with a fixed set of
 * conversations, not in CI.
 */
public class ScriptedChatModel implements ChatModel {

    /** One turn of the model: either a tool call or a final answer. */
    @FunctionalInterface
    public interface Step {
        ChatResponse respond(Prompt prompt);
    }

    private static final int MAX_TOOL_ITERATIONS = 8;

    private final ToolCallingManager toolCallingManager;
    private final List<ToolCallback> fallbackCallbacks;
    private final Deque<Step> steps = new ArrayDeque<>();
    private final List<Prompt> prompts = Collections.synchronizedList(new ArrayList<>());
    private final AtomicInteger invocations = new AtomicInteger();

    public ScriptedChatModel(ToolCallingManager toolCallingManager, List<ToolCallback> fallbackCallbacks) {
        this.toolCallingManager = toolCallingManager;
        this.fallbackCallbacks = List.copyOf(fallbackCallbacks);
    }

    public ScriptedChatModel script(Step... script) {
        steps.clear();
        Collections.addAll(steps, script);
        return this;
    }

    public void reset() {
        steps.clear();
        prompts.clear();
        invocations.set(0);
    }

    /** Zero proves a guardrail short-circuited the turn before any completion was requested. */
    public int invocationCount() {
        return invocations.get();
    }

    public List<Prompt> capturedPrompts() {
        return List.copyOf(prompts);
    }

    /** The system message of the first call, i.e. the prompt the retrieved passages went into. */
    public String firstSystemPrompt() {
        return prompts.isEmpty() ? null : prompts.get(0).getInstructions().stream()
                .filter(message -> message.getMessageType()
                        == org.springframework.ai.chat.messages.MessageType.SYSTEM)
                .map(org.springframework.ai.chat.messages.Message::getText)
                .findFirst()
                .orElse(null);
    }

    @Override
    public ChatResponse call(Prompt prompt) {
        Prompt current = withToolCallbacks(prompt);
        for (int iteration = 0; iteration < MAX_TOOL_ITERATIONS; iteration++) {
            invocations.incrementAndGet();
            prompts.add(current);

            Step step = steps.poll();
            if (step == null) {
                throw new IllegalStateException(
                        "ScriptedChatModel ran out of scripted steps after " + iteration + " turn(s). "
                        + "Add a step, or the code under test is calling the model more than expected.");
            }

            ChatResponse response = step.respond(current);
            if (!response.hasToolCalls()) {
                return response;
            }

            // The framework's own tool execution: schema binding, ToolContext
            // propagation and result serialisation all happen for real here.
            ToolExecutionResult execution = toolCallingManager.executeToolCalls(current, response);
            current = new Prompt(execution.conversationHistory(), current.getOptions());
        }
        throw new IllegalStateException("Tool loop did not terminate within " + MAX_TOOL_ITERATIONS);
    }

    /**
     * Ensures the prompt carries the tool callbacks the manager needs to resolve.
     * ChatClient normally sets them from {@code .tools(...)}; this keeps the double
     * from depending on that internal detail.
     */
    private Prompt withToolCallbacks(Prompt prompt) {
        if (prompt.getOptions() instanceof ToolCallingChatOptions options
            && !options.getToolCallbacks().isEmpty()) {
            return prompt;
        }
        ToolCallingChatOptions options = ToolCallingChatOptions.builder()
                .toolCallbacks(fallbackCallbacks)
                .build();
        if (prompt.getOptions() instanceof ToolCallingChatOptions existing) {
            options.setToolContext(existing.getToolContext());
        }
        return new Prompt(prompt.getInstructions(), options);
    }

    @Override
    public ChatOptions getDefaultOptions() {
        return ToolCallingChatOptions.builder().build();
    }

    // ---- script builders -------------------------------------------------

    public static Step toolCall(String toolName, String argumentsJson) {
        return prompt -> new ChatResponse(List.of(new Generation(
                AssistantMessage.builder()
                        .content("")
                        .toolCalls(List.of(new AssistantMessage.ToolCall(
                                UUID.randomUUID().toString(), "function", toolName, argumentsJson)))
                        .build(),
                ChatGenerationMetadata.builder().finishReason("TOOL_CALLS").build())));
    }

    public static Step reply(String text) {
        return prompt -> new ChatResponse(List.of(new Generation(new AssistantMessage(text),
                ChatGenerationMetadata.builder().finishReason("STOP").build())));
    }

    /** A final answer whose text is computed from the conversation so far. */
    public static Step replyFromConversation(java.util.function.Function<Prompt, String> answer) {
        return prompt -> new ChatResponse(List.of(new Generation(
                new AssistantMessage(answer.apply(prompt)),
                ChatGenerationMetadata.builder().finishReason("STOP").build())));
    }
}
