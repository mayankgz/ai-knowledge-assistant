package com.ai_knowledge_assistant.ai_knowledge_assistant;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.scheduling.annotation.EnableAsync;

@SpringBootApplication
@EnableCaching
@EnableAsync
public class AiKnowledgeAssistantApplication {

	public static void main(String[] args) {
		SpringApplication.run(AiKnowledgeAssistantApplication.class, args);
	}

}
