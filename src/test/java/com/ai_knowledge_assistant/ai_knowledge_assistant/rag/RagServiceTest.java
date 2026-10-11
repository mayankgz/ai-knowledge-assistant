package com.ai_knowledge_assistant.ai_knowledge_assistant.rag;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.filter.FilterExpressionBuilder;
import org.springframework.cache.CacheManager;
import org.springframework.cache.concurrent.ConcurrentMapCacheManager;
import org.springframework.http.codec.ServerSentEvent;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import reactor.core.publisher.Flux;

import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

// Plain unit test: @Cacheable/@CircuitBreaker/@Retry need Spring proxies, so they don't apply here
class RagServiceTest {

    private static final String USER = "alice@example.com";

    private VectorStore vectorStore;
    private ChatClient chatClient;
    private ChatClient.ChatClientRequestSpec requestSpec;
    private ChatClient.CallResponseSpec callSpec;
    private ChatClient.StreamResponseSpec streamSpec;
    private CacheManager cacheManager;
    private RagService ragService;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        vectorStore = mock(VectorStore.class);
        // One mock per step of the fluent chain chatClient.prompt().user(..).call()/.stream().content()
        chatClient = mock(ChatClient.class);
        requestSpec = mock(ChatClient.ChatClientRequestSpec.class);
        callSpec = mock(ChatClient.CallResponseSpec.class);
        streamSpec = mock(ChatClient.StreamResponseSpec.class);
        when(chatClient.prompt()).thenReturn(requestSpec);
        when(requestSpec.user(any(Consumer.class))).thenReturn(requestSpec);
        when(requestSpec.call()).thenReturn(callSpec);
        when(requestSpec.stream()).thenReturn(streamSpec);
        // Real in-memory cache and a default breaker: simple, and they behave like the real ones
        cacheManager = new ConcurrentMapCacheManager(RagService.CACHE_NAME);
        ragService = new RagService(vectorStore, chatClient, cacheManager, CircuitBreakerRegistry.ofDefaults());
    }

    private void llmAnswers(String answer) {
        when(callSpec.content()).thenReturn(answer);
    }

    private void llmStreams(Flux<String> tokens) {
        when(streamSpec.content()).thenReturn(tokens);
    }

    private List<ServerSentEvent<Object>> streamEvents(String question) {
        return ragService.streamQuery(question, USER).collectList().block();
    }

    private static List<String> names(List<ServerSentEvent<Object>> events) {
        return events.stream().map(ServerSentEvent::event).toList();
    }

    @SuppressWarnings("unchecked")
    private static String tokenText(ServerSentEvent<Object> event) {
        return ((Map<String, String>) event.data()).get("text");
    }

    private static Document chunk(String text, String filename) {
        return new Document(text, Map.of("filename", filename, "uploadedBy", USER));
    }

    @Test
    void searchIsFilteredToTheRequestingUser() {
        when(vectorStore.similaritySearch(any(SearchRequest.class))).thenReturn(List.of());

        ragService.query("What is Zephyr?", USER);

        ArgumentCaptor<SearchRequest> captor = ArgumentCaptor.forClass(SearchRequest.class);
        verify(vectorStore).similaritySearch(captor.capture());
        SearchRequest request = captor.getValue();
        assertThat(request.getQuery()).isEqualTo("What is Zephyr?");
        assertThat(request.getTopK()).isEqualTo(5);
        assertThat(request.getFilterExpression())
                .isEqualTo(new FilterExpressionBuilder().eq("uploadedBy", USER).build());
    }

    @Test
    void noMatchingChunksReturnsUploadHintWithoutCallingTheLlm() {
        when(vectorStore.similaritySearch(any(SearchRequest.class))).thenReturn(List.of());

        RagResponse response = ragService.query("What is Zephyr?", USER);

        assertThat(response.getAnswer()).contains("upload documents first");
        assertThat(response.getSources()).isEmpty();
        verify(chatClient, never()).prompt();
    }

    @Test
    void answersFromChunksAndListsEachSourceFileOnce() {
        when(vectorStore.similaritySearch(any(SearchRequest.class))).thenReturn(List.of(
                chunk("Zephyr's codename is BLUE-HERON.", "zephyr.txt"),
                chunk("Zephyr launches in March.", "zephyr.txt"),
                chunk("Budget is 2M.", "budget.pdf")));
        llmAnswers("The codename is BLUE-HERON.");

        RagResponse response = ragService.query("What is Zephyr's codename?", USER);

        assertThat(response.getAnswer()).isEqualTo("The codename is BLUE-HERON.");
        assertThat(response.getSources()).containsExactly("zephyr.txt", "budget.pdf");
    }

    @Test
    @SuppressWarnings("unchecked")
    void promptContainsChunkTextAndQuestion() {
        when(vectorStore.similaritySearch(any(SearchRequest.class)))
                .thenReturn(List.of(chunk("Zephyr's codename is BLUE-HERON.", "zephyr.txt")));
        llmAnswers("BLUE-HERON");

        ragService.query("What is Zephyr's codename?", USER);

        // Grab the lambda RagService passed to .user(..) and run it against a fake spec
        ArgumentCaptor<Consumer<ChatClient.PromptUserSpec>> captor = ArgumentCaptor.forClass(Consumer.class);
        verify(requestSpec).user(captor.capture());
        ChatClient.PromptUserSpec spec = mock(ChatClient.PromptUserSpec.class, RETURNS_SELF);
        captor.getValue().accept(spec);

        verify(spec).param(eq("context"), argThat(ctx ->
                ctx.toString().contains("Source: zephyr.txt") && ctx.toString().contains("BLUE-HERON")));
        verify(spec).param("question", "What is Zephyr's codename?");
    }

    @Test
    void fallbackReturnsUnavailableMessageWithNoSources() {
        RagResponse response = ragService.queryFallback("q", USER, new RuntimeException("Groq down"));

        assertThat(response.getAnswer()).contains("temporarily unavailable");
        assertThat(response.getSources()).isEmpty();
    }

    // ─── Streaming ──────────────────────────────────────────────────────────

    @Test
    void streamSendsSourcesThenEachTokenThenDone() {
        when(vectorStore.similaritySearch(any(SearchRequest.class)))
                .thenReturn(List.of(chunk("Zephyr's codename is BLUE-HERON.", "zephyr.txt")));
        llmStreams(Flux.just("The codename", " is\nBLUE-HERON."));

        List<ServerSentEvent<Object>> events = streamEvents("What is Zephyr's codename?");

        assertThat(names(events)).containsExactly("sources", "token", "token", "done");
        assertThat(events.get(0).data()).isEqualTo(List.of("zephyr.txt"));
        // Leading spaces and newlines inside tokens are preserved (they travel as JSON)
        assertThat(tokenText(events.get(1))).isEqualTo("The codename");
        assertThat(tokenText(events.get(2))).isEqualTo(" is\nBLUE-HERON.");
    }

    @Test
    void streamSearchIsFilteredToTheRequestingUser() {
        when(vectorStore.similaritySearch(any(SearchRequest.class))).thenReturn(List.of());

        streamEvents("What is Zephyr?");

        ArgumentCaptor<SearchRequest> captor = ArgumentCaptor.forClass(SearchRequest.class);
        verify(vectorStore).similaritySearch(captor.capture());
        assertThat(captor.getValue().getFilterExpression())
                .isEqualTo(new FilterExpressionBuilder().eq("uploadedBy", USER).build());
    }

    @Test
    void streamWithNoMatchingChunksSendsUploadHintWithoutCallingTheLlm() {
        when(vectorStore.similaritySearch(any(SearchRequest.class))).thenReturn(List.of());

        List<ServerSentEvent<Object>> events = streamEvents("What is Zephyr?");

        assertThat(names(events)).containsExactly("sources", "token", "done");
        assertThat(events.get(0).data()).isEqualTo(List.of());
        assertThat(tokenText(events.get(1))).contains("upload documents first");
        verify(chatClient, never()).prompt();
    }

    @Test
    void completedStreamIsCachedSoTheNextAskIsInstant() {
        when(vectorStore.similaritySearch(any(SearchRequest.class)))
                .thenReturn(List.of(chunk("Zephyr's codename is BLUE-HERON.", "zephyr.txt")));
        llmStreams(Flux.just("The codename", " is BLUE-HERON."));

        streamEvents("What is Zephyr's codename?");
        // Same question, different case/spacing: same cache key, so no second search or LLM call
        List<ServerSentEvent<Object>> second = streamEvents("  what is zephyr's codename?");

        RagResponse cached = cacheManager.getCache(RagService.CACHE_NAME)
                .get(RagService.cacheKey(USER, "What is Zephyr's codename?"), RagResponse.class);
        assertThat(cached.getAnswer()).isEqualTo("The codename is BLUE-HERON.");
        assertThat(cached.getSources()).containsExactly("zephyr.txt");

        assertThat(names(second)).containsExactly("sources", "token", "done");
        assertThat(tokenText(second.get(1))).isEqualTo("The codename is BLUE-HERON.");
        verify(vectorStore, times(1)).similaritySearch(any(SearchRequest.class));
        verify(chatClient, times(1)).prompt();
    }

    @Test
    void answerCachedByTheNonStreamingEndpointIsReusedByTheStream() {
        cacheManager.getCache(RagService.CACHE_NAME)
                .put(RagService.cacheKey(USER, "Budget?"), new RagResponse("2M", List.of("budget.pdf")));

        List<ServerSentEvent<Object>> events = streamEvents("Budget?");

        assertThat(names(events)).containsExactly("sources", "token", "done");
        assertThat(tokenText(events.get(1))).isEqualTo("2M");
        verifyNoInteractions(vectorStore);
    }

    @Test
    void llmFailureMidStreamEndsWithErrorAndCachesNothing() {
        when(vectorStore.similaritySearch(any(SearchRequest.class)))
                .thenReturn(List.of(chunk("Zephyr's codename is BLUE-HERON.", "zephyr.txt")));
        llmStreams(Flux.concat(Flux.just("Partial answer"), Flux.error(new RuntimeException("Groq down"))));

        List<ServerSentEvent<Object>> events = streamEvents("What is Zephyr's codename?");

        assertThat(names(events)).containsExactly("sources", "token", "error");
        assertThat(events.get(2).data()).isEqualTo(Map.of("message", RagService.UNAVAILABLE));
        assertThat(cacheManager.getCache(RagService.CACHE_NAME)
                .get(RagService.cacheKey(USER, "What is Zephyr's codename?"))).isNull();
    }

    @Test
    void searchFailureSendsOnlyAnErrorEvent() {
        when(vectorStore.similaritySearch(any(SearchRequest.class))).thenThrow(new RuntimeException("Chroma down"));

        List<ServerSentEvent<Object>> events = streamEvents("What is Zephyr?");

        assertThat(names(events)).containsExactly("error");
    }
}
