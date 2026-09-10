package com.ai_knowledge_assistant.ai_knowledge_assistant.agent;

import com.ai_knowledge_assistant.ai_knowledge_assistant.analytics.AnalyticsService;
import com.ai_knowledge_assistant.ai_knowledge_assistant.document.service.DocumentService;
import com.ai_knowledge_assistant.ai_knowledge_assistant.rag.RagService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class ToolExecuter {

    private final DocumentService documentService;
    private final RagService ragService;
    private final AnalyticsService analyticsService;
  //  private final PlannerService plannerService;

    public String execute(AgentDecision decision){

        String tool = decision.getTool();
        String input = decision.getInput();

        switch (tool) {
            case "DB_TOOL":
                return handleDbTool(input);
            case "VECTOR_TOOL":
                return handleVectorTool(input);

            case "ANALYTICS_TOOL":
                return handleAnalyticsTool(input);
            default:
                throw new RuntimeException("unknown tool" + tool);

        }
    }

    private String handleAnalyticsTool(String input) {

        return analyticsService.compute(input);
    }

    private String handleVectorTool(String input) {
        return ragService.search(input);
    }

    private String handleDbTool(String input) {
        return documentService.getDocument(input);
    }

}
