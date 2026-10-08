package com.ai_knowledge_assistant.ai_knowledge_assistant;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.boot.test.context.SpringBootTest;

// Boots the whole app, so it needs the docker-compose services and Ollama running.
// Skipped by default; run with: INTEGRATION_TESTS=true ./mvnw test
@EnabledIfEnvironmentVariable(named = "INTEGRATION_TESTS", matches = "true")
@SpringBootTest
class AiKnowledgeAssistantApplicationTests {

	@Test
	void contextLoads() {
	}

}
