package com.ai_knowledge_assistant.ai_knowledge_assistant.agent;

import com.fasterxml.jackson.core.JsonProcessingException;
import jakarta.validation.constraints.NotBlank;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/agent")
@RequiredArgsConstructor
public class AgentController {

    private final PlannerService plannerService;

    private  final  AgentService agentService;

    @GetMapping
    public String decideTool(@RequestParam @NotBlank String question,
                                    @AuthenticationPrincipal UserDetails userDetails) throws JsonProcessingException {
        return agentService.process(question);
    }
}
