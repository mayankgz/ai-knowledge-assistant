package com.ai_knowledge_assistant.ai_knowledge_assistant.document.service;

import com.ai_knowledge_assistant.ai_knowledge_assistant.document.entity.Document;
import com.ai_knowledge_assistant.ai_knowledge_assistant.document.model.DocumentResponse;
import com.ai_knowledge_assistant.ai_knowledge_assistant.document.repository.DocumentRepository;
import com.ai_knowledge_assistant.ai_knowledge_assistant.kafka.DocumentIndexingProducer;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.tika.Tika;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class DocumentService {

    private final DocumentIndexingProducer documentIndexingProducer;
    private final DocumentRepository documentRepository;
    private final Tika tika = new Tika();

    public DocumentResponse upload(MultipartFile file , String emailID){

        try{
            String content = tika.parseToString(file.getInputStream());
            if(content == null || content.isBlank()){
                throw new IllegalArgumentException("Could not extract content from the file");
            }
            Document document = Document.builder()
                    .filename(file.getOriginalFilename())
                    .uploadedBy(emailID)
                    .fileSize(file.getSize())
                    .contentType(file.getContentType())
                    .build();
            documentRepository.save(document);
            log.info("Document saved: {} by {}", document.getId(), emailID);

            documentIndexingProducer.sendDocumentEvent(
                    document.getId()
                    ,file.getOriginalFilename()
                    ,content
            );
            return  DocumentResponse.from(document);
        }catch (Exception e) {
            log.error("Failed to upload document: {}", file.getOriginalFilename(), e);
            throw new RuntimeException("Failed to process document: " + e.getMessage());
        }

    }

    public List<DocumentResponse> getDocuments(String userEmail) {
        return documentRepository.findByUploadedByOrderByUploadedAtDesc(userEmail)
                .stream()
                .map(DocumentResponse::from)
                .toList();
    }
    public void deleteDocument(Long id, String userEmail) {
        Document doc = documentRepository.findById(id)
                .orElseThrow(() -> new RuntimeException("Document not found"));

        if (!doc.getUploadedBy().equals(userEmail)) {
            throw new RuntimeException("Not authorized to delete this document");
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
