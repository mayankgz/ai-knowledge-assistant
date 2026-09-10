package com.ai_knowledge_assistant.ai_knowledge_assistant.rag;

import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.github.resilience4j.retry.annotation.Retry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.common.protocol.types.Field;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Slf4j
public class RagService {

    private final VectorStore vectorStore;
    private final ChatClient chatClient;

    @Cacheable(value = "rag-queries", key = "#question.toLowerCase().trim()")
    @CircuitBreaker(name = "groqApi" , fallbackMethod = "queryFallback")
    @Retry(name = "groqApi")
    public  RagResponse query(String question){

        List<Document> relevantDocs = vectorStore.similaritySearch(
                SearchRequest.builder()
                        .query(question)
                        .topK(5)
                        .build()
        );
        if (relevantDocs == null) {
            return RagResponse.builder()
                    .answer("No relevant documents found. Please upload documents first.")
                    .sources(List.of())
                    .build();
        }

        // 2. Build context from chunks
        String context = relevantDocs.stream()
                .map(doc -> String.format("Source: %s\n%s",
                        doc.getMetadata().getOrDefault("filename", "unknown"),
                        doc.getText()))
                .collect(Collectors.joining("\n\n---\n\n"));

        // 3. Call Groq LLM with context
        String answer = chatClient.prompt()
                .user(u -> u.text("""
                Context from uploaded documents:
                
                {context}
                
                ---
                
                Question: {question}
                
                Answer based only on the context above:
                """)
                        .param("context", context)
                        .param("question", question)
                )
                .call()
                .content();
        // 4. Build sources list
        List<String> sources = relevantDocs.stream()
                .map(doc -> doc.getMetadata().getOrDefault("filename", "unknown").toString())
                .distinct()
                .toList();

        log.info("Query answered using {} chunks from: {}", relevantDocs.size(), sources);

        return RagResponse.builder()
                .answer(answer)
                .sources(sources)
                .build();
    }

    // Fallback — called when circuit is OPEN or retries exhausted
    public RagResponse queryFallback(String question, Exception e) {
        log.error("AI service unavailable for question: {}", question, e);
        return RagResponse.builder()
                .answer("AI service is temporarily unavailable. Please try again in a moment.")
                .sources(List.of())
                .build();
    }
    public  String search(String input){
        return """
Summary:
In January, supplier costs increased due to raw material price hikes and delivery delays.
""";
    }
}
