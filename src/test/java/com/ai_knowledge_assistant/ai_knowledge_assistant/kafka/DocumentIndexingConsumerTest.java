package com.ai_knowledge_assistant.ai_knowledge_assistant.kafka;

import com.ai_knowledge_assistant.ai_knowledge_assistant.document.entity.Document;
import com.ai_knowledge_assistant.ai_knowledge_assistant.document.entity.Document.IndexingStatus;
import com.ai_knowledge_assistant.ai_knowledge_assistant.document.repository.DocumentRepository;
import com.ai_knowledge_assistant.ai_knowledge_assistant.rag.DocumentIndexingService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
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
                .uploadedBy("alice@example.com").status(IndexingStatus.INDEXING).build();
        when(repository.findById(7L)).thenReturn(Optional.of(doc));
    }

    @Test
    void passesTheDocumentIdToTheIndexingService() throws Exception {
        consumer.onDocumentUpload("{\"documentId\":7}");

        verify(indexingService).indexDocument(7L);
    }

    @Test
    void messagesInTheOldFormatStillParse() throws Exception {
        // Events sent before this change also carried filename/content/uploadedBy
        consumer.onDocumentUpload("{\"documentId\":7,\"filename\":\"zephyr.txt\",\"content\":\"hello\"}");

        verify(indexingService).indexDocument(7L);
    }

    @Test
    void indexingFailureMarksDocumentFailedWithTheReason() throws Exception {
        doThrow(new IllegalStateException("Batch 2/3 failed after 3 attempts: Ollama down"))
                .when(indexingService).indexDocument(7L);

        consumer.onDocumentUpload("{\"documentId\":7}");

        assertThat(doc.getStatus()).isEqualTo(IndexingStatus.FAILED);
        assertThat(doc.getErrorMessage()).isEqualTo("Batch 2/3 failed after 3 attempts: Ollama down");
    }

    @Test
    void unreadableMessageIsSwallowedWithoutTouchingTheDatabase() {
        consumer.onDocumentUpload("not json");

        verifyNoInteractions(repository, indexingService);
    }

    @Test
    void failureToSaveFailedStatusDoesNotEscapeTheListener() throws Exception {
        doThrow(new IllegalStateException("Ollama down")).when(indexingService).indexDocument(7L);
        when(repository.save(any())).thenThrow(new RuntimeException("DB down"));

        consumer.onDocumentUpload("{\"documentId\":7}");  // must not throw

        verify(repository).save(doc);
    }
}
