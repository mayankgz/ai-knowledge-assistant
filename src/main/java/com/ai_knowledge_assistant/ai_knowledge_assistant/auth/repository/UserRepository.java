package com.ai_knowledge_assistant.ai_knowledge_assistant.auth.repository;

import com.ai_knowledge_assistant.ai_knowledge_assistant.auth.entity.User;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface UserRepository extends JpaRepository<User, Long> {

    Optional<User> findByEmail(String email);
    boolean existsByEmail(String email);
}
