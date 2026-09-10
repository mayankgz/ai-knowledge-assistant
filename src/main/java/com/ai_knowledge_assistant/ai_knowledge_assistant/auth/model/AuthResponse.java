package com.ai_knowledge_assistant.ai_knowledge_assistant.auth.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;

@Data
@Builder
@AllArgsConstructor
public class AuthResponse {
    private String token;
    private String email;
    private String name;
    private String role;
}