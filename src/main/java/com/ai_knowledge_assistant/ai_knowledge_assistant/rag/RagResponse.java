package com.ai_knowledge_assistant.ai_knowledge_assistant.rag;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.util.List;

@Data
@NoArgsConstructor
@Builder
@AllArgsConstructor
public class RagResponse implements Serializable {
    private String answer;
    private List<String> sources;
}
