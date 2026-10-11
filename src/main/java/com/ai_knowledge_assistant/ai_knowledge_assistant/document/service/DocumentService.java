package com.ai_knowledge_assistant.ai_knowledge_assistant.document.service;

import com.ai_knowledge_assistant.ai_knowledge_assistant.document.entity.Document;
import com.ai_knowledge_assistant.ai_knowledge_assistant.document.model.DocumentResponse;
import com.ai_knowledge_assistant.ai_knowledge_assistant.document.repository.DocumentRepository;
import com.ai_knowledge_assistant.ai_knowledge_assistant.exception.ForbiddenException;
import com.ai_knowledge_assistant.ai_knowledge_assistant.exception.ResourceNotFoundException;
import com.ai_knowledge_assistant.ai_knowledge_assistant.kafka.DocumentIndexingProducer;
import com.ai_knowledge_assistant.ai_knowledge_assistant.rag.DocumentIndexingService;
import com.ai_knowledge_assistant.ai_knowledge_assistant.storage.DocumentStorage;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class DocumentService {

    private final DocumentIndexingProducer documentIndexingProducer;
    private final DocumentRepository documentRepository;
    private final DocumentIndexingService documentIndexingService;
    private final DocumentStorage documentStorage;

    // Only stores the file; text extraction happens later in the indexing consumer,
    // so large or slow-to-parse files don't hold up the request
    public DocumentResponse upload(MultipartFile file , String emailID){
        Document document = Document.builder()
                .filename(file.getOriginalFilename())
                .uploadedBy(emailID)
                .fileSize(file.getSize())
                .contentType(file.getContentType())
                .build();
        documentRepository.save(document);

        try (InputStream in = file.getInputStream()) {
            documentStorage.save(document.getId(), in);
        } catch (IOException e) {
            // Don't leave a row behind that can never be indexed
            documentRepository.delete(document);
            log.error("Failed to store document: {}", file.getOriginalFilename(), e);
            throw new UncheckedIOException("Failed to store document", e);
        }
        log.info("Document saved: {} by {}", document.getId(), emailID);

        documentIndexingProducer.sendDocumentEvent(document.getId());
        return  DocumentResponse.from(document);
    }

    public List<DocumentResponse> getDocuments(String userEmail) {
        return documentRepository.findByUploadedByOrderByUploadedAtDesc(userEmail)
                .stream()
                .map(DocumentResponse::from)
                .toList();
    }
    // Cache keys are "email:question" and can't be evicted per user, so clear all cached answers
    @CacheEvict(value = "rag-queries", allEntries = true)
    public void deleteDocument(Long id, String userEmail) {
        Document doc = documentRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Document not found"));

        if (!doc.getUploadedBy().equals(userEmail)) {
            throw new ForbiddenException("Not authorized to delete this document");
        }

        // Row goes last: if anything before it fails, the row survives and the delete can be retried
        documentIndexingService.deleteChunks(id);
        try {
            documentStorage.delete(id);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to delete stored file for document " + id, e);
        }
        documentRepository.delete(doc);
        log.info("Document deleted: {} by {}", id, userEmail);
    }

    public String getDocument(String input){
        return """
Documents in January:
1. Supplier cost increased by 12%
2. Delivery delays impacted logistics
3. Raw material price fluctuations observed
""";
    }

}
