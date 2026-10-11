package com.ai_knowledge_assistant.ai_knowledge_assistant.document.model;

import com.ai_knowledge_assistant.ai_knowledge_assistant.document.entity.Document;
import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@Builder
public class DocumentResponse {
    private Long id;
    private String filename;
    private String contentType;
    private Long fileSize;
    private String status;
    private LocalDateTime uploadedAt;
    private LocalDateTime indexedAt;
    private Integer batchesDone;
    private Integer batchesTotal;
    private String errorMessage;

    public static DocumentResponse from(Document doc) {
        return DocumentResponse.builder()
                .id(doc.getId())
                .filename(doc.getFilename())
                .contentType(doc.getContentType())
                .fileSize(doc.getFileSize())
                .status(doc.getStatus().name())
                .uploadedAt(doc.getUploadedAt())
                .indexedAt(doc.getIndexedAt())
                .batchesDone(doc.getBatchesDone())
                .batchesTotal(doc.getBatchesTotal())
                .errorMessage(doc.getErrorMessage())
                .build();
    }
}
