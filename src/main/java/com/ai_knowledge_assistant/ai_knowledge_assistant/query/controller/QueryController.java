package com.ai_knowledge_assistant.ai_knowledge_assistant.query.controller;

import com.ai_knowledge_assistant.ai_knowledge_assistant.rag.RagResponse;
import com.ai_knowledge_assistant.ai_knowledge_assistant.rag.RagService;
import jakarta.validation.constraints.NotBlank;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/query")
@RequiredArgsConstructor
public class QueryController {

    private final RagService ragService;

    @PostMapping
    public ResponseEntity<RagResponse> query(
            @RequestParam @NotBlank String question,
            @AuthenticationPrincipal UserDetails userDetails) {

        RagResponse response = ragService.query(question);
        return ResponseEntity.ok(response);
    }

}
