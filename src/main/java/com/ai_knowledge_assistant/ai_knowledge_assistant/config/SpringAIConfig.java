package com.ai_knowledge_assistant.ai_knowledge_assistant.config;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class SpringAIConfig {

    @Bean
    public ChatClient chatClient(@Qualifier("openAiChatModel") ChatModel model){
        return ChatClient.builder(model)
                .defaultSystem("""
                                        You are a helpful knowledge assistant.
                                        Answer questions based ONLY on the context provided.
                                        If the answer is not in the context, say "I don't have that information in the uploaded documents."
                                        Always be concise and accurate.
                                        Cite which document the information comes from when possible.
                        """)
                .build();
    }
}
