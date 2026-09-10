// document/entity/Document.java
package com.ai_knowledge_assistant.ai_knowledge_assistant.document.entity;

import jakarta.persistence.*;
import lombok.*;
import java.time.LocalDateTime;

@Entity
@Table(name = "documents")
@Getter @Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Document {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String filename;

    @Column(nullable = false)
    private String contentType;

    @Column(nullable = false)
    private Long fileSize;

    @Column(nullable = false)
    private String uploadedBy; // user email

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private IndexingStatus status;

    @Column(columnDefinition = "TEXT")
    private String errorMessage;

    @Column(nullable = false, updatable = false)
    private LocalDateTime uploadedAt;

    @Column
    private LocalDateTime indexedAt;

    @PrePersist
    protected void onCreate() {
        uploadedAt = LocalDateTime.now();
        status = IndexingStatus.PENDING;
    }

    public enum IndexingStatus {
        PENDING,     // uploaded, waiting to index
        INDEXING,    // being processed
        INDEXED,     // ready for search
        FAILED       // indexing failed
    }
}