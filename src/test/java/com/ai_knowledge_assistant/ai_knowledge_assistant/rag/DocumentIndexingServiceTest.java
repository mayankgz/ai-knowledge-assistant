package com.ai_knowledge_assistant.ai_knowledge_assistant.rag;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.filter.Filter;
import org.springframework.ai.vectorstore.filter.FilterExpressionBuilder;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class DocumentIndexingServiceTest {

    private VectorStore vectorStore;
    private DocumentIndexingService indexingService;

    @BeforeEach
    void setUp() {
        vectorStore = mock(VectorStore.class);
        indexingService = new DocumentIndexingService(vectorStore);
    }

    @Test
    @SuppressWarnings("unchecked")
    void everyChunkCarriesDocumentIdFilenameAndOwner() {
        // Long enough to be split into several chunks (splitter uses ~500-token chunks)
        String content = "Zephyr is a logistics project. ".repeat(400);

        indexingService.indexDocument(7L, content, "zephyr.txt", "alice@example.com");

        ArgumentCaptor<List<Document>> captor = ArgumentCaptor.forClass(List.class);
        verify(vectorStore).add(captor.capture());
        List<Document> chunks = captor.getValue();
        assertThat(chunks).hasSizeGreaterThan(1);
        assertThat(chunks).allSatisfy(chunk -> assertThat(chunk.getMetadata())
                .containsEntry("documentId", "7")
                .containsEntry("filename", "zephyr.txt")
                .containsEntry("uploadedBy", "alice@example.com"));
    }

    @Test
    void deleteMatchesChunksByDocumentIdMetadata() {
        indexingService.deleteDocument(7L);

        Filter.Expression expected = new FilterExpressionBuilder().eq("documentId", "7").build();
        verify(vectorStore).delete(expected);
    }
}
