package com.ai_knowledge_assistant.ai_knowledge_assistant.auth.controller;

import com.ai_knowledge_assistant.ai_knowledge_assistant.auth.model.AuthResponse;
import com.ai_knowledge_assistant.ai_knowledge_assistant.auth.model.LoginRequest;
import com.ai_knowledge_assistant.ai_knowledge_assistant.auth.model.RegisterRequest;
import com.ai_knowledge_assistant.ai_knowledge_assistant.auth.service.AuthService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;


@RestController
@RequestMapping("/api/v1/auth")
@RequiredArgsConstructor
public class AuthController {

    private final AuthService authService;

    @PostMapping("/register")
    public ResponseEntity<AuthResponse> register(@Valid @RequestBody RegisterRequest request){
        return ResponseEntity.status(201).body(authService.register(request));
    }

    @PostMapping("/login")
    public ResponseEntity<AuthResponse> login(@Valid @RequestBody LoginRequest request){
        return ResponseEntity.status(201).body(authService.login(request));
    }

}
