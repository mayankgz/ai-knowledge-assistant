package com.ai_knowledge_assistant.ai_knowledge_assistant.kafka;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

import java.util.Map;

@Component
@RequiredArgsConstructor
@Slf4j
public class DocumentIndexingProducer {

    private final KafkaTemplate<String,Object> kafkaTemplate;
    private static final String TOPIC = "document-indexing";

    // Only the ID travels: the consumer loads the file from storage and the rest from the DB,
    // so the message stays a few bytes no matter how large the document is
    public void sendDocumentEvent(Long documentId){

        Map<String,Object> event = Map.of("documentId", documentId);
        kafkaTemplate.send(TOPIC,documentId.toString(),event);
        log.info("Indexing Event sent for Document {}",documentId);
    }


}
