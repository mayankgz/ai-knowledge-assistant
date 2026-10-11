package com.ai_knowledge_assistant.ai_knowledge_assistant.kafka;

import com.ai_knowledge_assistant.ai_knowledge_assistant.document.entity.Document;
import com.ai_knowledge_assistant.ai_knowledge_assistant.document.repository.DocumentRepository;
import com.ai_knowledge_assistant.ai_knowledge_assistant.rag.DocumentIndexingService;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

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
        Long documentId = null;
        try{
            Map<String, Object> event = objectMapper.readValue(message, Map.class);
            documentId = Long.valueOf(event.get("documentId").toString());
            log.info("Received indexing event for document: {}", documentId);

            // Status and progress updates happen inside, batch by batch
            indexingService.indexDocument(documentId);

        } catch (Exception e) {
            log.error("Failed to process indexing event for document {}", documentId, e);
            if (documentId != null) {
                markFailed(documentId, e);
            }
        }

    }

    private void markFailed(Long documentId, Exception cause) {
        try {
            documentRepository.findById(documentId).ifPresent(doc -> {
                doc.setStatus(Document.IndexingStatus.FAILED);
                doc.setErrorMessage(cause.getMessage());
                documentRepository.save(doc);
            });
        } catch (Exception e) {
            // The DB itself may be why indexing failed — don't let this mask the original error
            log.error("Could not mark document {} as FAILED", documentId, e);
        }
    }
}
