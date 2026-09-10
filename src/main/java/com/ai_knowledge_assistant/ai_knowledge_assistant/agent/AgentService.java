package com.ai_knowledge_assistant.ai_knowledge_assistant.agent;

import com.fasterxml.jackson.core.JsonProcessingException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class AgentService {

    private final PlannerService plannerService;
    private final ToolExecuter toolExecutor;

    private static  int MAX_STEPS = 3;

    public String process(String query) throws JsonProcessingException {

        String context ="";
        for(int i = 0; i < MAX_STEPS ;i++){

            AgentDecision decision = plannerService.decide(query + "\ncontext:\n" + context);
            System.out.println(query + "\ncontext:\n" + context);
            if("FINAL".equalsIgnoreCase(decision.getTool())){
                return decision.getInput();
            }

            String result = toolExecutor.execute(decision);

            context += """
                PREVIOUS STEP:
                TOOL USED: %s
                RESULT: %s
                You MUST use the above RESULT DATA to answer. Do NOT assume or hallucinate.    
                """.formatted(decision.getTool(), result);
            System.out.println("STEP " + i);
            System.out.println("DECISION: " + decision.getTool());
            System.out.println("INPUT: " + decision.getInput());

        }
        return "Could not complete the request";
    }
}
