package com.ai_knowledge_assistant.ai_knowledge_assistant.agent;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.apache.kafka.common.protocol.types.Field;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class PlannerService {

    private final ChatClient chatClient;


    public AgentDecision decide(String query) throws JsonProcessingException {
        String prompt = buildPrompt(query);
        String response = chatClient.prompt().user(u->u.text(prompt)).call().content();
        return  parseResponse(response);
    }

    private String buildPrompt(String input) {
        return """
You are an AI agent.

You must complete the task in a few steps.

Available tools:
1. DB_TOOL → fetch documents
2. VECTOR_TOOL → summarize/analyze content
3. ANALYTICS_TOOL → calculations

STRICT RULES:
- Do NOT repeat the same tool more than once
- After VECTOR_TOOL → you MUST return FINAL answer
- Use tools only when necessary
- Stop once enough information is available
You must base your answer ONLY on the provided tool results.
Do NOT make assumptions.
Do NOT say "assuming".

If you return FINAL:
- Use only the tool results provided in context
- Do NOT add assumptions
- Do NOT mention tools
- Give a clean user-facing answer

FORMAT:

TOOL: <tool_name>
INPUT: <input>

OR

TOOL: FINAL
INPUT: <final answer>

Respond ONLY in JSON:

{
  "tool": "<tool_name>",
  "input": "<input>"
}

Input:
%s
""".formatted(input);
    }
    private AgentDecision parseResponse(String response) throws JsonProcessingException {

        AgentDecision decision = new AgentDecision();
        ObjectMapper mapper = new ObjectMapper();
        decision = mapper.readValue(response, AgentDecision.class);

//        String[] lines = response.split("\n");
//
//        for (String line : lines) {
//            if (line.toUpperCase().startsWith("TOOL:")){
//                decision.setTool(line.replace("TOOL:", "").trim());
//            } else if (line.startsWith("INPUT:")) {
//                decision.setInput(line.replace("INPUT:", "").trim());
//            }
//        }

        return decision;
    }

}
