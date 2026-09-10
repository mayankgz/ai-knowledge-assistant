package com.ai_knowledge_assistant.ai_knowledge_assistant.analytics;

import org.apache.kafka.common.protocol.types.Field;
import org.springframework.stereotype.Service;

@Service
public class AnalyticsService {

    public String compute(String input){
        return "Analytic Service";
    }
}
