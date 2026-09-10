package com.ai_knowledge_assistant.ai_knowledge_assistant.document.repository;

import com.ai_knowledge_assistant.ai_knowledge_assistant.document.entity.Document;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface DocumentRepository extends JpaRepository<Document, Long> {

    List<Document> findByUploadedByOrderByUploadedAtDesc(String email);
}
