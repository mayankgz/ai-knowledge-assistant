package com.ai_knowledge_assistant.ai_knowledge_assistant.rag;

import com.ai_knowledge_assistant.ai_knowledge_assistant.document.entity.Document.IndexingStatus;
import com.ai_knowledge_assistant.ai_knowledge_assistant.document.repository.DocumentRepository;
import com.ai_knowledge_assistant.ai_knowledge_assistant.storage.DocumentStorage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.filter.Filter;
import org.springframework.ai.vectorstore.filter.FilterExpressionBuilder;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.file.NoSuchFileException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.*;

class DocumentIndexingServiceTest {

    private VectorStore vectorStore;
    private DocumentRepository repository;
    private DocumentStorage storage;
    private com.ai_knowledge_assistant.ai_knowledge_assistant.document.entity.Document record;

    @BeforeEach
    void setUp() {
        vectorStore = mock(VectorStore.class);
        repository = mock(DocumentRepository.class);
        storage = mock(DocumentStorage.class);

        record = com.ai_knowledge_assistant.ai_knowledge_assistant.document.entity.Document.builder()
                .id(7L).filename("zephyr.txt").uploadedBy("alice@example.com")
                .status(IndexingStatus.PENDING).build();
        when(repository.findById(7L)).thenReturn(Optional.of(record));
        when(repository.existsById(7L)).thenReturn(true);
    }

    // Retry backoff of 0 keeps the retry tests instant
    private DocumentIndexingService service(int batchSize, int maxAttempts) {
        return new DocumentIndexingService(vectorStore, repository, storage, batchSize, maxAttempts, 0);
    }

    private void storedFile(String text) throws IOException {
        // A fresh stream per open(), like reading the file again from disk
        when(storage.open(7L)).thenAnswer(inv -> new ByteArrayInputStream(text.getBytes(UTF_8)));
    }

    private static String numberedLines(int count) {
        return IntStream.rangeClosed(1, count)
                .mapToObj(i -> "Line " + i + " of the Zephyr logistics report.")
                .collect(Collectors.joining("\n"));
    }

    @SuppressWarnings("unchecked")
    private List<List<Document>> batchesSentToVectorStore() {
        ArgumentCaptor<List<Document>> captor = ArgumentCaptor.forClass(List.class);
        verify(vectorStore, atLeastOnce()).add(captor.capture());
        return captor.getAllValues();
    }

    private List<Document> allStoredChunks() {
        return batchesSentToVectorStore().stream().flatMap(List::stream).toList();
    }

    @Test
    void indexesTheWholeDocumentPastTheOldTikaAndChunkLimits() throws IOException {
        // ~2.3M characters: far past Tika's old 100k-character default and the old 1,000-chunk cap
        storedFile(numberedLines(70_000));

        service(100, 1).indexDocument(7L);

        List<Document> chunks = allStoredChunks();
        String allText = chunks.stream().map(Document::getText).collect(Collectors.joining());
        assertThat(chunks).hasSizeGreaterThan(1000);
        assertThat(allText).contains("Line 1 of").contains("Line 70000 of");
        // With a chunk cap the splitter doesn't drop the rest: it dumps it all into one giant
        // last chunk, too big to embed meaningfully. Every chunk must stay ~500 tokens.
        assertThat(chunks).allSatisfy(chunk -> assertThat(chunk.getText().length()).isLessThan(4000));
        assertThat(record.getStatus()).isEqualTo(IndexingStatus.INDEXED);
    }

    @Test
    void storesChunksInBatchesAndRecordsProgressAfterEach() throws IOException {
        storedFile(numberedLines(700));
        // Record progress as seen at the moment each batch is stored
        List<Integer> doneBeforeEachBatch = new ArrayList<>();
        doAnswer(inv -> { doneBeforeEachBatch.add(record.getBatchesDone()); return null; })
                .when(vectorStore).add(anyList());

        service(5, 1).indexDocument(7L);

        List<List<Document>> batches = batchesSentToVectorStore();
        int chunkCount = batches.stream().mapToInt(List::size).sum();
        assertThat(batches).allSatisfy(batch -> assertThat(batch).hasSizeLessThanOrEqualTo(5));
        assertThat(batches).hasSize((chunkCount + 4) / 5);
        assertThat(record.getBatchesTotal()).isEqualTo(batches.size());
        assertThat(doneBeforeEachBatch).isEqualTo(IntStream.range(0, batches.size()).boxed().toList());
        assertThat(record.getBatchesDone()).isEqualTo(batches.size());
        assertThat(record.getStatus()).isEqualTo(IndexingStatus.INDEXED);
        assertThat(record.getIndexedAt()).isNotNull();
    }

    @Test
    void chunksGetDeterministicIdsAndCarryOwnerMetadata() throws IOException {
        storedFile(numberedLines(700));

        service(100, 1).indexDocument(7L);

        List<Document> chunks = allStoredChunks();
        assertThat(chunks).extracting(Document::getId)
                .isEqualTo(IntStream.range(0, chunks.size()).mapToObj(i -> "7:" + i).toList());
        assertThat(chunks).allSatisfy(chunk -> assertThat(chunk.getMetadata())
                .containsEntry("documentId", "7")
                .containsEntry("filename", "zephyr.txt")
                .containsEntry("uploadedBy", "alice@example.com"));
    }

    @Test
    void transientBatchFailureIsRetriedAndIndexingCompletes() throws IOException {
        storedFile(numberedLines(700));
        doThrow(new RuntimeException("Ollama hiccup")).doNothing().when(vectorStore).add(anyList());

        service(5, 3).indexDocument(7L);

        assertThat(record.getStatus()).isEqualTo(IndexingStatus.INDEXED);
        assertThat(record.getBatchesDone()).isEqualTo(record.getBatchesTotal());
        // One extra call: the first batch's retry
        verify(vectorStore, times(record.getBatchesTotal() + 1)).add(anyList());
    }

    @Test
    void batchFailingEveryAttemptStopsAndKeepsEarlierBatches() throws IOException {
        storedFile(numberedLines(700));
        // Batch 1 succeeds; every later call fails
        doNothing().doThrow(new RuntimeException("Ollama down")).when(vectorStore).add(anyList());

        assertThatThrownBy(() -> service(5, 2).indexDocument(7L))
                .hasMessageContaining("Batch 2/")
                .hasMessageContaining("after 2 attempts")
                .hasMessageContaining("Ollama down");

        assertThat(record.getBatchesDone()).isEqualTo(1);
        verify(vectorStore, times(3)).add(anyList());              // batch 1, then batch 2 twice
        verify(vectorStore, never()).delete(any(Filter.Expression.class));  // batch 1 stays stored
    }

    @Test
    void fileWithNoTextFailsWithAClearReason() throws IOException {
        storedFile("   \n  ");

        assertThatThrownBy(() -> service(100, 1).indexDocument(7L))
                .hasMessage("No text could be extracted from the file");
        verify(vectorStore, never()).add(anyList());
    }

    @Test
    void missingStoredFileFailsWithAClearReason() throws IOException {
        when(storage.open(7L)).thenThrow(new NoSuchFileException("./data/uploads/7"));

        assertThatThrownBy(() -> service(100, 1).indexDocument(7L))
                .hasMessage("Uploaded file not found in storage");
    }

    @Test
    void documentDeletedDuringIndexingStopsAndRemovesStoredChunks() throws IOException {
        storedFile(numberedLines(700));
        // Still there before batch 1, gone before batch 2
        when(repository.existsById(7L)).thenReturn(true, false);

        service(5, 1).indexDocument(7L);

        verify(vectorStore, times(1)).add(anyList());
        verify(vectorStore).delete(new FilterExpressionBuilder().eq("documentId", "7").build());
        assertThat(record.getStatus()).isNotEqualTo(IndexingStatus.INDEXED);
    }

    @Test
    void documentDeletedDuringTheLastBatchStillGetsCleanedUp() throws IOException {
        // ~12 chunks with batch size 100 → exactly one batch, so batch 1 is also the last
        storedFile(numberedLines(700));
        // Still there before the only batch, gone by the check after the loop
        when(repository.existsById(7L)).thenReturn(true, false);

        service(100, 1).indexDocument(7L);

        verify(vectorStore, times(1)).add(anyList());
        verify(vectorStore).delete(new FilterExpressionBuilder().eq("documentId", "7").build());
        assertThat(record.getStatus()).isEqualTo(IndexingStatus.INDEXING);   // never marked INDEXED
        assertThat(record.getIndexedAt()).isNull();
    }

    @Test
    void invalidSettingsAreRejectedAtStartup() {
        assertThatThrownBy(() -> new DocumentIndexingService(vectorStore, repository, storage, 0, 3, 1000))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("indexing.batch-size");
        assertThatThrownBy(() -> new DocumentIndexingService(vectorStore, repository, storage, 100, 0, 1000))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("indexing.batch-max-attempts");
        assertThatThrownBy(() -> new DocumentIndexingService(vectorStore, repository, storage, 100, 3, -1))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("indexing.retry-backoff-ms");
    }

    @Test
    void deleteChunksMatchesByDocumentIdMetadata() {
        service(100, 1).deleteChunks(7L);

        Filter.Expression expected = new FilterExpressionBuilder().eq("documentId", "7").build();
        verify(vectorStore).delete(expected);
    }
}
