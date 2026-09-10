package com.ai_knowledge_assistant.ai_knowledge_assistant.document.controller;

import com.ai_knowledge_assistant.ai_knowledge_assistant.document.model.DocumentResponse;
import com.ai_knowledge_assistant.ai_knowledge_assistant.document.service.DocumentService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

@RestController
@RequestMapping("/api/v1/documents")
@RequiredArgsConstructor
public class DocumentController {

    private  final DocumentService documentService;

    @PostMapping
    public ResponseEntity<DocumentResponse> upload(
            @RequestParam("file") MultipartFile file,
            @AuthenticationPrincipal UserDetails userDetails) {

        if (file.isEmpty()) {
            return ResponseEntity.badRequest().build();
        }

        DocumentResponse response = documentService.upload(file, userDetails.getUsername());
        return ResponseEntity.status(201).body(response);
    }
    @GetMapping
    public ResponseEntity<List<DocumentResponse>> getDocuments(
            @AuthenticationPrincipal UserDetails userDetails) {
        return ResponseEntity.ok(
                documentService.getDocuments(userDetails.getUsername())
        );
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deleteDocument(
            @PathVariable Long id,
            @AuthenticationPrincipal UserDetails userDetails) {
        documentService.deleteDocument(id, userDetails.getUsername());
        return ResponseEntity.noContent().build();
    }

}
