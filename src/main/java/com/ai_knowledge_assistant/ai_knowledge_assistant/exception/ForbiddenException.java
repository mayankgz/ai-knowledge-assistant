package com.ai_knowledge_assistant.ai_knowledge_assistant.exception;

// Not Spring Security's AccessDeniedException: that one is meant for the security filter chain
public class ForbiddenException extends RuntimeException {
    public ForbiddenException(String message) {
        super(message);
    }
}
