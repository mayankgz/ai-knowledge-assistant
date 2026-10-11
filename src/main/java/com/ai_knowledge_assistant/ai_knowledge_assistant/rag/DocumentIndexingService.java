package com.ai_knowledge_assistant.ai_knowledge_assistant.rag;

import com.ai_knowledge_assistant.ai_knowledge_assistant.document.entity.Document.IndexingStatus;
import com.ai_knowledge_assistant.ai_knowledge_assistant.document.repository.DocumentRepository;
import com.ai_knowledge_assistant.ai_knowledge_assistant.storage.DocumentStorage;
import lombok.extern.slf4j.Slf4j;
import org.apache.tika.Tika;
import org.apache.tika.exception.TikaException;
import org.springframework.ai.document.Document;
import org.springframework.ai.transformer.splitter.TokenTextSplitter;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.filter.FilterExpressionBuilder;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.NoSuchFileException;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Service
@Slf4j
public class DocumentIndexingService {

    private final VectorStore vectorStore;
    private final DocumentRepository documentRepository;
    private final DocumentStorage documentStorage;
    private final int batchSize;
    private final int maxAttempts;
    private final long retryBackoffMs;

    public DocumentIndexingService(VectorStore vectorStore,
                                   DocumentRepository documentRepository,
                                   DocumentStorage documentStorage,
                                   @Value("${indexing.batch-size}") int batchSize,
                                   @Value("${indexing.batch-max-attempts}") int maxAttempts,
                                   @Value("${indexing.retry-backoff-ms}") long retryBackoffMs) {
        // Fail at startup on bad settings: a batch size of 0 would make partition() loop forever
        if (batchSize < 1) {
            throw new IllegalArgumentException("indexing.batch-size must be at least 1, was " + batchSize);
        }
        if (maxAttempts < 1) {
            throw new IllegalArgumentException("indexing.batch-max-attempts must be at least 1, was " + maxAttempts);
        }
        if (retryBackoffMs < 0) {
            throw new IllegalArgumentException("indexing.retry-backoff-ms must not be negative, was " + retryBackoffMs);
        }
        this.vectorStore = vectorStore;
        this.documentRepository = documentRepository;
        this.documentStorage = documentStorage;
        this.batchSize = batchSize;
        this.maxAttempts = maxAttempts;
        this.retryBackoffMs = retryBackoffMs;
    }

    // Extract → chunk → embed and store batch by batch, recording progress on the document row
    public void indexDocument(Long documentId) throws IOException {
        var record = documentRepository.findById(documentId)
                .orElseThrow(() -> new IllegalStateException("Document row not found"));
        record.setStatus(IndexingStatus.INDEXING);
        record.setErrorMessage(null);
        documentRepository.save(record);

        String text = extractText(documentId);
        if (text.isBlank()) {
            throw new IllegalStateException("No text could be extracted from the file");
        }

        List<Document> chunks = split(documentId, text, record.getFilename(), record.getUploadedBy());
        List<List<Document>> batches = partition(chunks, batchSize);
        log.info("Document {} split into {} chunks, {} batches", documentId, chunks.size(), batches.size());

        record.setBatchesTotal(batches.size());
        record.setBatchesDone(0);
        documentRepository.save(record);

        for (int i = 0; i < batches.size(); i++) {
            if (deletedDuringIndexing(documentId, i)) {
                return;
            }
            addWithRetry(documentId, i, batches);
            record.setBatchesDone(i + 1);
            documentRepository.save(record);
        }
        // Once more after the loop: a delete during the last batch would otherwise leave its chunks behind
        if (deletedDuringIndexing(documentId, batches.size())) {
            return;
        }

        record.setStatus(IndexingStatus.INDEXED);
        record.setIndexedAt(LocalDateTime.now());
        documentRepository.save(record);
        log.info("Document {} indexed: {} chunks stored in ChromaDB", documentId, chunks.size());
    }

    // Removes the document's chunks from the vector store only; the row and stored file are not touched
    public void deleteChunks(Long documentId) {
        // Match on the documentId metadata so every chunk of the document goes, however many there are
        vectorStore.delete(new FilterExpressionBuilder().eq("documentId", documentId.toString()).build());
        log.info("Chunks for document {} removed from vector store", documentId);
    }

    // The user may delete the document while indexing runs. Their delete only removes the chunks
    // stored by then, so remove any stored since, and tell the caller to stop.
    private boolean deletedDuringIndexing(Long documentId, int batchesStored) {
        if (documentRepository.existsById(documentId)) {
            return false;
        }
        deleteChunks(documentId);
        log.info("Document {} was deleted during indexing, stopped after {} batches", documentId, batchesStored);
        return true;
    }

    private String extractText(Long documentId) throws IOException {
        Tika tika = new Tika();
        tika.setMaxStringLength(-1);   // default silently stops at 100,000 characters
        try (InputStream in = documentStorage.open(documentId)) {
            return tika.parseToString(in);
        } catch (NoSuchFileException e) {
            // e.g. documents uploaded before files were stored, whose text only travelled in Kafka
            throw new IllegalStateException("Uploaded file not found in storage", e);
        } catch (TikaException e) {
            throw new IllegalStateException("Could not extract text: " + e.getMessage(), e);
        }
    }

    private List<Document> split(Long documentId, String text, String filename, String uploadedBy) {
        Document whole = new Document(text, Map.of(
                "documentId", documentId.toString(),
                "filename", filename,
                "uploadedBy", uploadedBy));

        // No chunk cap: past maxNumChunks (it was 1,000) the splitter dumps all remaining text
        // into one giant final chunk, far too big to embed or send to the LLM
        TokenTextSplitter splitter = new TokenTextSplitter(500, 50, 5, Integer.MAX_VALUE, true);
        List<Document> pieces = splitter.apply(List.of(whole));

        // Deterministic IDs ("{docId}:{index}"): Chroma upserts by ID, so a retried batch
        // overwrites its chunks instead of duplicating them
        List<Document> chunks = new ArrayList<>(pieces.size());
        for (int i = 0; i < pieces.size(); i++) {
            Document piece = pieces.get(i);
            chunks.add(new Document(documentId + ":" + i, piece.getText(), piece.getMetadata()));
        }
        return chunks;
    }

    private void addWithRetry(Long documentId, int batchIndex, List<List<Document>> batches) {
        for (int attempt = 1; ; attempt++) {
            try {
                vectorStore.add(batches.get(batchIndex));
                return;
            } catch (RuntimeException e) {
                if (attempt >= maxAttempts) {
                    throw new IllegalStateException("Batch %d/%d failed after %d attempts: %s"
                            .formatted(batchIndex + 1, batches.size(), attempt, e.getMessage()), e);
                }
                log.warn("Batch {}/{} of document {} failed (attempt {}/{}), retrying",
                        batchIndex + 1, batches.size(), documentId, attempt, maxAttempts, e);
                sleep(retryBackoffMs * attempt);
            }
        }
    }

    private static <T> List<List<T>> partition(List<T> items, int size) {
        List<List<T>> parts = new ArrayList<>();
        for (int i = 0; i < items.size(); i += size) {
            parts.add(items.subList(i, Math.min(i + size, items.size())));
        }
        return parts;
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while retrying a batch", e);
        }
    }
}
