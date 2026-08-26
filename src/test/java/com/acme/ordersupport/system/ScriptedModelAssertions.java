package com.acme.ordersupport.system;

import com.acme.ordersupport.testsupport.ScriptedChatModel;
import org.springframework.ai.chat.messages.MessageType;

/** Script steps that make the tool result itself visible in the reply, so a test can assert on it. */
final class ScriptedModelAssertions {

    private ScriptedModelAssertions() {
    }

    /**
     * Answers with the raw text of the last tool response. Lets a test assert that
     * the tool's JSON genuinely reached the model, rather than trusting that a
     * scripted sentence implies it did.
     */
    static ScriptedChatModel.Step replyEchoingToolResult() {
        return ScriptedChatModel.replyFromConversation(prompt -> prompt.getInstructions().stream()
                .filter(message -> message.getMessageType() == MessageType.TOOL)
                .map(Object::toString)
                .reduce((first, second) -> second)
                .orElse("no tool response present"));
    }
}
