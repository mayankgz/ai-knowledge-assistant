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

    public void sendDocumentEvent(Long documentId , String fileName , String content){

        Map<String,Object> event = Map.of(
                "documentId" ,documentId,
                "filename",fileName,
                "content",content

        );
        kafkaTemplate.send(TOPIC,documentId.toString(),event);
        log.info("Indexing Event sent for Document {}",documentId);
    }


}
