package com.ai_knowledge_assistant.ai_knowledge_assistant.kafka;

import com.ai_knowledge_assistant.ai_knowledge_assistant.document.entity.Document;
import com.ai_knowledge_assistant.ai_knowledge_assistant.document.entity.Document.IndexingStatus;
import com.ai_knowledge_assistant.ai_knowledge_assistant.document.repository.DocumentRepository;
import com.ai_knowledge_assistant.ai_knowledge_assistant.rag.DocumentIndexingService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

class DocumentIndexingConsumerTest {

    private DocumentIndexingService indexingService;
    private DocumentRepository repository;
    private DocumentIndexingConsumer consumer;
    private Document doc;

    @BeforeEach
    void setUp() {
        indexingService = mock(DocumentIndexingService.class);
        repository = mock(DocumentRepository.class);
        consumer = new DocumentIndexingConsumer(indexingService, repository, new ObjectMapper());

        doc = Document.builder().id(7L).filename("zephyr.txt")
                .uploadedBy("alice@example.com").status(IndexingStatus.PENDING).build();
        when(repository.findById(7L)).thenReturn(Optional.of(doc));
    }

    private static String event(boolean withOwner) {
        return withOwner
                ? "{\"documentId\":7,\"filename\":\"zephyr.txt\",\"content\":\"hello\",\"uploadedBy\":\"alice@example.com\"}"
                : "{\"documentId\":7,\"filename\":\"zephyr.txt\",\"content\":\"hello\"}";
    }

    @Test
    void successfulIndexingMovesStatusThroughIndexingToIndexed() {
        // Record the status at the moment indexing runs, since doc is mutated afterwards
        AtomicReference<IndexingStatus> statusDuringIndexing = new AtomicReference<>();
        doAnswer(inv -> { statusDuringIndexing.set(doc.getStatus()); return null; })
                .when(indexingService).indexDocument(any(), any(), any(), any());

        consumer.onDocumentUpload(event(true));

        verify(indexingService).indexDocument(7L, "hello", "zephyr.txt", "alice@example.com");
        assertThat(statusDuringIndexing.get()).isEqualTo(IndexingStatus.INDEXING);
        assertThat(doc.getStatus()).isEqualTo(IndexingStatus.INDEXED);
        assertThat(doc.getIndexedAt()).isNotNull();
    }

    @Test
    void indexingFailureMarksDocumentFailed() {
        doThrow(new RuntimeException("Ollama down"))
                .when(indexingService).indexDocument(any(), any(), any(), any());

        consumer.onDocumentUpload(event(true));

        assertThat(doc.getStatus()).isEqualTo(IndexingStatus.FAILED);
        assertThat(doc.getIndexedAt()).isNull();
    }

    @Test
    void eventWithoutOwnerFallsBackToDocumentRow() {
        consumer.onDocumentUpload(event(false));

        verify(indexingService).indexDocument(7L, "hello", "zephyr.txt", "alice@example.com");
        assertThat(doc.getStatus()).isEqualTo(IndexingStatus.INDEXED);
    }

    @Test
    void unreadableMessageIsSwallowedWithoutTouchingTheDatabase() {
        consumer.onDocumentUpload("not json");

        verifyNoInteractions(repository, indexingService);
    }

    @Test
    void failureToSaveFailedStatusDoesNotEscapeTheListener() {
        doThrow(new RuntimeException("Ollama down"))
                .when(indexingService).indexDocument(any(), anyString(), anyString(), anyString());
        // First save (INDEXING) works; the FAILED save blows up, e.g. the DB went away
        when(repository.save(any())).thenReturn(doc).thenThrow(new RuntimeException("DB down"));

        consumer.onDocumentUpload(event(true));  // must not throw

        verify(repository, times(2)).save(doc);
    }
}
