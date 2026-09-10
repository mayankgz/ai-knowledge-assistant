package com.ai_knowledge_assistant.ai_knowledge_assistant.auth.model;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class RegisterRequest {

    @NotBlank
    private String name;

    @Email
    @NotBlank
    private String email;

    @NotBlank
    @Size(min =6 ,message = "Password must be at least 6 Characters")
    private String password;

}
