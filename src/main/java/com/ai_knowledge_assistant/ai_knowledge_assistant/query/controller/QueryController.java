package com.ai_knowledge_assistant.ai_knowledge_assistant.query.controller;

import com.ai_knowledge_assistant.ai_knowledge_assistant.rag.RagResponse;
import com.ai_knowledge_assistant.ai_knowledge_assistant.rag.RagService;
import jakarta.validation.constraints.NotBlank;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;

@RestController
@RequestMapping("/api/v1/query")
@RequiredArgsConstructor
public class QueryController {

    private final RagService ragService;

    @PostMapping
    public ResponseEntity<RagResponse> query(
            @RequestParam @NotBlank String question,
            @AuthenticationPrincipal UserDetails userDetails) {

        RagResponse response = ragService.query(question, userDetails.getUsername());
        return ResponseEntity.ok(response);
    }

    // Server-sent events: "sources", then "token" fragments as the LLM writes, then "done" (or "error")
    @PostMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ServerSentEvent<Object>> stream(
            @RequestParam @NotBlank String question,
            @AuthenticationPrincipal UserDetails userDetails) {

        return ragService.streamQuery(question, userDetails.getUsername());
    }

}
