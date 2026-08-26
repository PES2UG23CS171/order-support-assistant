package com.acme.ordersupport.agent;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

/**
 * Holds the chat client, or holds nothing.
 *
 * <p>A missing model provider used to stop the application booting: the
 * {@code ChatClient} was a hard constructor dependency of the agent, so with no
 * API key configured the process died at startup and order lookups, returns,
 * refunds and policy search -- none of which need a model -- died with it.
 *
 * <p>One unconfigured optional dependency should degrade one endpoint, not the
 * service. The chat route now answers 503 while everything else keeps working,
 * which also makes the offline demo real rather than theoretical: the whole
 * retrieval and tool stack can be exercised with no model provider at all.
 */
@Component
public class ChatClientProvider {

    private static final Logger log = LoggerFactory.getLogger(ChatClientProvider.class);

    private final ChatClient chatClient;

    public ChatClientProvider(ObjectProvider<ChatModel> chatModels,
                              ObjectProvider<ChatClient.Builder> builders) {
        // Keyed off the model rather than the builder. The builder's bean
        // definition always exists, and asking an ObjectProvider for it when no
        // model is configured propagates the creation failure instead of
        // returning null -- which is the same startup crash in a new place.
        this.chatClient = chatModels.getIfAvailable() != null ? builders.getObject().build() : null;
        if (this.chatClient == null) {
            log.warn("No chat model is configured. POST /api/v1/chat will answer 503; "
                     + "orders, returns, refunds and policy search are unaffected.");
        }
    }

    public boolean available() {
        return chatClient != null;
    }

    public ChatClient require() {
        if (chatClient == null) {
            throw new AssistantUnavailableException();
        }
        return chatClient;
    }
}
