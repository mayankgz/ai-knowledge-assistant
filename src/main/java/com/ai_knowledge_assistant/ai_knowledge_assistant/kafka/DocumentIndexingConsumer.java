package com.ai_knowledge_assistant.ai_knowledge_assistant.kafka;

import com.ai_knowledge_assistant.ai_knowledge_assistant.document.entity.Document;
import com.ai_knowledge_assistant.ai_knowledge_assistant.document.repository.DocumentRepository;
import com.ai_knowledge_assistant.ai_knowledge_assistant.rag.DocumentIndexingService;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.Map;

@Component
@RequiredArgsConstructor
@Slf4j
public class DocumentIndexingConsumer {

    private final DocumentIndexingService indexingService;
    private final DocumentRepository documentRepository;
    private final ObjectMapper objectMapper;


    @KafkaListener(topics = "document-indexing" , groupId = "indexing-group")
    public void onDocumentUpload(String message){
        try{
            Map<String, Object> event = objectMapper.readValue(message, Map.class);
            Long documentId = Long.valueOf(event.get("documentId").toString());
            String content = event.get("content").toString();
            String filename = event.get("filename").toString();

            log.info("Received indexing event for document: {}", documentId);

            // Update status to INDEXING
            documentRepository.findById(documentId).ifPresent(doc -> {
                doc.setStatus(Document.IndexingStatus.INDEXING);
                documentRepository.save(doc);
            });

            // Index into ChromaDB
            indexingService.indexDocument(documentId, content, filename);

            // Update status to INDEXED
            documentRepository.findById(documentId).ifPresent(doc -> {
                doc.setStatus(Document.IndexingStatus.INDEXED);
                doc.setIndexedAt(LocalDateTime.now());
                documentRepository.save(doc);
            });

            log.info("Document indexed successfully: {}", documentId);

        } catch (Exception e) {
            log.error("Failed to process indexing event", e);
        }

    }
}
