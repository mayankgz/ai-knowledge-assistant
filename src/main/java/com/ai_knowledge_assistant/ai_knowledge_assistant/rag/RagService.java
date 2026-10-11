package com.ai_knowledge_assistant.ai_knowledge_assistant.rag;

import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.github.resilience4j.reactor.circuitbreaker.operator.CircuitBreakerOperator;
import io.github.resilience4j.retry.annotation.Retry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.filter.FilterExpressionBuilder;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Slf4j
public class RagService {

    static final String CACHE_NAME = "rag-queries";
    static final String NO_DOCUMENTS = "No relevant documents found. Please upload documents first.";
    static final String UNAVAILABLE = "AI service is temporarily unavailable. Please try again in a moment.";

    private static final String PROMPT = """
            Context from uploaded documents:

            {context}

            ---

            Question: {question}

            Answer based only on the context above:
            """;

    private final VectorStore vectorStore;
    private final ChatClient chatClient;
    private final CacheManager cacheManager;
    private final CircuitBreakerRegistry circuitBreakerRegistry;

    // Shared by @Cacheable below and the streaming path, so both read and write the same entries
    public static String cacheKey(String userEmail, String question) {
        return userEmail + ":" + question.toLowerCase().trim();
    }

    // Skip caching sourceless answers (no docs yet, or the fallback) so they don't stick for 30 min
    @Cacheable(value = CACHE_NAME, key = "T(com.ai_knowledge_assistant.ai_knowledge_assistant.rag.RagService).cacheKey(#userEmail, #question)",
            unless = "#result.sources.isEmpty()")
    @CircuitBreaker(name = "groqApi" , fallbackMethod = "queryFallback")
    @Retry(name = "groqApi")
    public  RagResponse query(String question, String userEmail){

        List<Document> relevantDocs = retrieve(question, userEmail);
        if (relevantDocs.isEmpty()) {
            return RagResponse.builder()
                    .answer(NO_DOCUMENTS)
                    .sources(List.of())
                    .build();
        }

        String answer = prompt(question, relevantDocs).call().content();
        List<String> sources = sourcesOf(relevantDocs);

        log.info("Query answered using {} chunks from: {}", relevantDocs.size(), sources);

        return RagResponse.builder()
                .answer(answer)
                .sources(sources)
                .build();
    }

    // Fallback — called when circuit is OPEN or retries exhausted
    public RagResponse queryFallback(String question, String userEmail, Exception e) {
        log.error("AI service unavailable for question: {}", question, e);
        return RagResponse.builder()
                .answer(UNAVAILABLE)
                .sources(List.of())
                .build();
    }

    /**
     * Same answer as {@link #query}, delivered as server-sent events while the LLM writes it:
     * {@code sources} (filename list) first, then one {@code token} per text fragment, then
     * {@code done}; or a single {@code error}. Event data is always JSON, so whitespace and
     * newlines inside tokens survive the SSE format intact.
     */
    public Flux<ServerSentEvent<Object>> streamQuery(String question, String userEmail) {
        String key = cacheKey(userEmail, question);
        RagResponse cached = cachedAnswer(key);
        if (cached != null) {
            return Flux.just(sourcesEvent(cached.getSources()), tokenEvent(cached.getAnswer()), doneEvent());
        }

        // The similarity search embeds the question via Ollama and queries Chroma: blocking I/O,
        // so it runs on a worker thread rather than the request thread
        return Mono.fromCallable(() -> retrieve(question, userEmail))
                .subscribeOn(Schedulers.boundedElastic())
                .flatMapMany(docs -> docs.isEmpty()
                        ? Flux.just(sourcesEvent(List.of()), tokenEvent(NO_DOCUMENTS), doneEvent())
                        : streamAnswer(question, docs, key))
                .onErrorResume(e -> {
                    // No retry: once tokens have been sent, retrying would repeat text the user already saw
                    log.error("Streaming answer failed for question: {}", question, e);
                    return Flux.just(errorEvent(UNAVAILABLE));
                });
    }

    private Flux<ServerSentEvent<Object>> streamAnswer(String question, List<Document> docs, String key) {
        List<String> sources = sourcesOf(docs);
        StringBuilder answer = new StringBuilder();

        Flux<ServerSentEvent<Object>> tokens = prompt(question, docs).stream().content()
                // Same breaker instance as query(), so failures on either path open it for both
                .transformDeferred(CircuitBreakerOperator.of(circuitBreakerRegistry.circuitBreaker("groqApi")))
                .doOnNext(answer::append)
                .map(this::tokenEvent)
                .doOnComplete(() -> {
                    cacheAnswer(key, new RagResponse(answer.toString(), sources));
                    log.info("Streamed answer using {} chunks from: {}", docs.size(), sources);
                });

        return Flux.concat(Flux.just(sourcesEvent(sources)), tokens, Flux.just(doneEvent()));
    }

    // Only search chunks owned by the requesting user
    private List<Document> retrieve(String question, String userEmail) {
        List<Document> docs = vectorStore.similaritySearch(
                SearchRequest.builder()
                        .query(question)
                        .topK(5)
                        .filterExpression(new FilterExpressionBuilder().eq("uploadedBy", userEmail).build())
                        .build()
        );
        return docs == null ? List.of() : docs;
    }

    private ChatClient.ChatClientRequestSpec prompt(String question, List<Document> docs) {
        String context = docs.stream()
                .map(doc -> String.format("Source: %s\n%s",
                        doc.getMetadata().getOrDefault("filename", "unknown"),
                        doc.getText()))
                .collect(Collectors.joining("\n\n---\n\n"));

        return chatClient.prompt()
                .user(u -> u.text(PROMPT)
                        .param("context", context)
                        .param("question", question)
                );
    }

    private static List<String> sourcesOf(List<Document> docs) {
        return docs.stream()
                .map(doc -> doc.getMetadata().getOrDefault("filename", "unknown").toString())
                .distinct()
                .toList();
    }

    // Cache trouble (e.g. Redis down) must not break answering, so it's treated as a miss
    private RagResponse cachedAnswer(String key) {
        try {
            Cache cache = cacheManager.getCache(CACHE_NAME);
            return cache == null ? null : cache.get(key, RagResponse.class);
        } catch (RuntimeException e) {
            log.warn("Could not read cached answer, continuing without cache", e);
            return null;
        }
    }

    private void cacheAnswer(String key, RagResponse response) {
        if (response.getSources().isEmpty()) {
            return;
        }
        try {
            Cache cache = cacheManager.getCache(CACHE_NAME);
            if (cache != null) {
                cache.put(key, response);
            }
        } catch (RuntimeException e) {
            log.warn("Could not cache streamed answer", e);
        }
    }

    private ServerSentEvent<Object> sourcesEvent(List<String> sources) {
        return ServerSentEvent.builder((Object) sources).event("sources").build();
    }

    private ServerSentEvent<Object> tokenEvent(String text) {
        return ServerSentEvent.builder((Object) Map.of("text", text)).event("token").build();
    }

    private ServerSentEvent<Object> doneEvent() {
        return ServerSentEvent.builder((Object) Map.of()).event("done").build();
    }

    private ServerSentEvent<Object> errorEvent(String message) {
        return ServerSentEvent.builder((Object) Map.of("message", message)).event("error").build();
    }

    public  String search(String input){
        return """
Summary:
In January, supplier costs increased due to raw material price hikes and delivery delays.
""";
    }
}
