package com.ai_knowledge_assistant.ai_knowledge_assistant.document.service;

import com.ai_knowledge_assistant.ai_knowledge_assistant.document.entity.Document;
import com.ai_knowledge_assistant.ai_knowledge_assistant.document.repository.DocumentRepository;
import com.ai_knowledge_assistant.ai_knowledge_assistant.exception.ForbiddenException;
import com.ai_knowledge_assistant.ai_knowledge_assistant.exception.ResourceNotFoundException;
import com.ai_knowledge_assistant.ai_knowledge_assistant.kafka.DocumentIndexingProducer;
import com.ai_knowledge_assistant.ai_knowledge_assistant.rag.DocumentIndexingService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.mock.web.MockMultipartFile;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class DocumentServiceTest {

    private static final String OWNER = "alice@example.com";

    private DocumentIndexingProducer producer;
    private DocumentRepository repository;
    private DocumentIndexingService indexingService;
    private DocumentService documentService;

    @BeforeEach
    void setUp() {
        producer = mock(DocumentIndexingProducer.class);
        repository = mock(DocumentRepository.class);
        indexingService = mock(DocumentIndexingService.class);
        documentService = new DocumentService(producer, repository, indexingService);
    }

    private Document storedDoc() {
        Document doc = Document.builder().id(7L).filename("zephyr.txt").uploadedBy(OWNER).build();
        when(repository.findById(7L)).thenReturn(Optional.of(doc));
        return doc;
    }

    @Test
    void uploadSavesDocumentAndPublishesEventWithOwner() {
        // No real DB, so simulate what saving does: Postgres assigns the ID, @PrePersist sets status/time
        when(repository.save(any(Document.class))).thenAnswer(inv -> {
            Document d = inv.getArgument(0);
            d.setId(42L);
            d.setStatus(Document.IndexingStatus.PENDING);
            d.setUploadedAt(java.time.LocalDateTime.now());
            return d;
        });
        MockMultipartFile file = new MockMultipartFile(
                "file", "zephyr.txt", "text/plain", "Zephyr's codename is BLUE-HERON.".getBytes());

        documentService.upload(file, OWNER);

        verify(repository).save(argThat(d -> d.getUploadedBy().equals(OWNER)
                && d.getFilename().equals("zephyr.txt")));
        verify(producer).sendDocumentEvent(eq(42L), eq("zephyr.txt"),
                argThat(text -> text.contains("BLUE-HERON")), eq(OWNER));
    }

    @Test
    void deletingMissingDocumentThrowsNotFound() {
        when(repository.findById(7L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> documentService.deleteDocument(7L, OWNER))
                .isInstanceOf(ResourceNotFoundException.class);
        verifyNoInteractions(indexingService);
        verify(repository, never()).delete(any());
    }

    @Test
    void deletingSomeoneElsesDocumentThrowsForbiddenAndDeletesNothing() {
        storedDoc();

        assertThatThrownBy(() -> documentService.deleteDocument(7L, "mallory@example.com"))
                .isInstanceOf(ForbiddenException.class);
        verifyNoInteractions(indexingService);
        verify(repository, never()).delete(any());
    }

    @Test
    void ownerDeleteRemovesChunksBeforeTheRow() {
        Document doc = storedDoc();

        documentService.deleteDocument(7L, OWNER);

        InOrder order = inOrder(indexingService, repository);
        order.verify(indexingService).deleteDocument(7L);
        order.verify(repository).delete(doc);
    }

    @Test
    void failedChunkDeleteKeepsTheRowSoDeleteCanBeRetried() {
        storedDoc();
        doThrow(new RuntimeException("Chroma down")).when(indexingService).deleteDocument(7L);

        assertThatThrownBy(() -> documentService.deleteDocument(7L, OWNER))
                .hasMessageContaining("Chroma down");
        verify(repository, never()).delete(any());
    }

    @Test
    void getDocumentsOnlyQueriesTheCallersDocuments() {
        when(repository.findByUploadedByOrderByUploadedAtDesc(OWNER)).thenReturn(java.util.List.of());

        assertThat(documentService.getDocuments(OWNER)).isEmpty();
        verify(repository).findByUploadedByOrderByUploadedAtDesc(OWNER);
    }
}
