package com.ai_knowledge_assistant.ai_knowledge_assistant.rag;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.filter.FilterExpressionBuilder;

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
    private RagService ragService;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        vectorStore = mock(VectorStore.class);
        // One mock per step of the fluent chain chatClient.prompt().user(..).call().content()
        chatClient = mock(ChatClient.class);
        requestSpec = mock(ChatClient.ChatClientRequestSpec.class);
        callSpec = mock(ChatClient.CallResponseSpec.class);
        when(chatClient.prompt()).thenReturn(requestSpec);
        when(requestSpec.user(any(Consumer.class))).thenReturn(requestSpec);
        when(requestSpec.call()).thenReturn(callSpec);
        ragService = new RagService(vectorStore, chatClient);
    }

    private void llmAnswers(String answer) {
        when(callSpec.content()).thenReturn(answer);
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
}
