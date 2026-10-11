package com.ai_knowledge_assistant.ai_knowledge_assistant.document.service;

import com.ai_knowledge_assistant.ai_knowledge_assistant.document.entity.Document;
import com.ai_knowledge_assistant.ai_knowledge_assistant.document.repository.DocumentRepository;
import com.ai_knowledge_assistant.ai_knowledge_assistant.exception.ForbiddenException;
import com.ai_knowledge_assistant.ai_knowledge_assistant.exception.ResourceNotFoundException;
import com.ai_knowledge_assistant.ai_knowledge_assistant.kafka.DocumentIndexingProducer;
import com.ai_knowledge_assistant.ai_knowledge_assistant.rag.DocumentIndexingService;
import com.ai_knowledge_assistant.ai_knowledge_assistant.storage.DocumentStorage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.mock.web.MockMultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import static java.nio.charset.StandardCharsets.UTF_8;
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
    private DocumentStorage storage;
    private DocumentService documentService;

    @BeforeEach
    void setUp() {
        producer = mock(DocumentIndexingProducer.class);
        repository = mock(DocumentRepository.class);
        indexingService = mock(DocumentIndexingService.class);
        storage = mock(DocumentStorage.class);
        documentService = new DocumentService(producer, repository, indexingService, storage);
    }

    private void saveAssignsId42() {
        // No real DB, so simulate what saving does: Postgres assigns the ID, @PrePersist sets status/time
        when(repository.save(any(Document.class))).thenAnswer(inv -> {
            Document d = inv.getArgument(0);
            d.setId(42L);
            d.setStatus(Document.IndexingStatus.PENDING);
            d.setUploadedAt(LocalDateTime.now());
            return d;
        });
    }

    private static MockMultipartFile zephyrFile() {
        return new MockMultipartFile("file", "zephyr.txt", "text/plain", "Zephyr's codename is BLUE-HERON.".getBytes(UTF_8));
    }

    private Document storedDoc() {
        Document doc = Document.builder().id(7L).filename("zephyr.txt").uploadedBy(OWNER).build();
        when(repository.findById(7L)).thenReturn(Optional.of(doc));
        return doc;
    }

    @Test
    void uploadSavesRowThenFileThenSendsIdOnlyEvent() throws IOException {
        saveAssignsId42();
        AtomicReference<String> storedBytes = new AtomicReference<>();
        doAnswer(inv -> { storedBytes.set(new String(inv.<InputStream>getArgument(1).readAllBytes(), UTF_8)); return null; })
                .when(storage).save(eq(42L), any(InputStream.class));

        documentService.upload(zephyrFile(), OWNER);

        InOrder order = inOrder(repository, storage, producer);
        order.verify(repository).save(argThat(d -> d.getUploadedBy().equals(OWNER) && d.getFilename().equals("zephyr.txt")));
        order.verify(storage).save(eq(42L), any(InputStream.class));
        order.verify(producer).sendDocumentEvent(42L);
        assertThat(storedBytes.get()).isEqualTo("Zephyr's codename is BLUE-HERON.");
    }

    @Test
    void uploadThatCannotStoreTheFileRemovesTheRowAndSendsNoEvent() throws IOException {
        saveAssignsId42();
        doThrow(new IOException("disk full")).when(storage).save(eq(42L), any(InputStream.class));

        assertThatThrownBy(() -> documentService.upload(zephyrFile(), OWNER))
                .isInstanceOf(UncheckedIOException.class);
        verify(repository).delete(argThat(d -> d.getId() == 42L));
        verifyNoInteractions(producer);
    }

    @Test
    void deletingMissingDocumentThrowsNotFound() {
        when(repository.findById(7L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> documentService.deleteDocument(7L, OWNER))
                .isInstanceOf(ResourceNotFoundException.class);
        verifyNoInteractions(indexingService, storage);
        verify(repository, never()).delete(any());
    }

    @Test
    void deletingSomeoneElsesDocumentThrowsForbiddenAndDeletesNothing() {
        storedDoc();

        assertThatThrownBy(() -> documentService.deleteDocument(7L, "mallory@example.com"))
                .isInstanceOf(ForbiddenException.class);
        verifyNoInteractions(indexingService, storage);
        verify(repository, never()).delete(any());
    }

    @Test
    void ownerDeleteRemovesChunksThenFileThenRow() throws IOException {
        Document doc = storedDoc();

        documentService.deleteDocument(7L, OWNER);

        InOrder order = inOrder(indexingService, storage, repository);
        order.verify(indexingService).deleteChunks(7L);
        order.verify(storage).delete(7L);
        order.verify(repository).delete(doc);
    }

    @Test
    void failedChunkDeleteKeepsFileAndRowSoDeleteCanBeRetried() throws IOException {
        storedDoc();
        doThrow(new RuntimeException("Chroma down")).when(indexingService).deleteChunks(7L);

        assertThatThrownBy(() -> documentService.deleteDocument(7L, OWNER))
                .hasMessageContaining("Chroma down");
        verify(storage, never()).delete(any());
        verify(repository, never()).delete(any());
    }

    @Test
    void getDocumentsOnlyQueriesTheCallersDocuments() {
        when(repository.findByUploadedByOrderByUploadedAtDesc(OWNER)).thenReturn(List.of());

        assertThat(documentService.getDocuments(OWNER)).isEmpty();
        verify(repository).findByUploadedByOrderByUploadedAtDesc(OWNER);
    }
}
