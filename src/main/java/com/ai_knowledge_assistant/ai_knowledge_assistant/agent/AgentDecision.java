package com.ai_knowledge_assistant.ai_knowledge_assistant.agent;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@AllArgsConstructor
@NoArgsConstructor
public class AgentDecision {

    private  String tool;
    private String input;
}
