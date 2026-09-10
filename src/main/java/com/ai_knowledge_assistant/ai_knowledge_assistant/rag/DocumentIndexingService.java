package com.ai_knowledge_assistant.ai_knowledge_assistant.rag;

import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.ai.transformer.splitter.TokenTextSplitter;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

@Service
@Slf4j
@AllArgsConstructor
public class DocumentIndexingService {

    private final VectorStore vectorStore;

    public void indexDocument(Long documentId,String content,String fileName){
        Document doc = new Document(
                content,
                Map.of(
                        "documentId",documentId.toString(),
                        "filename",fileName
                )
        );

        //Chunks
        TokenTextSplitter splitter = new TokenTextSplitter(500,50,5,1000,true);
        List<Document> chunks = splitter.apply(List.of(doc));

        log.info("Document {} split into {} chunks", documentId, chunks.size());

        // 3. Embed + store in ChromaDB (Spring AI handles embedding automatically)
        vectorStore.add(chunks);

        log.info("Document {} indexed: {} chunks stored in ChromaDB", documentId, chunks.size());
    }

    public void deleteDocument(Long documentId) {
        // Delete all chunks for this document from ChromaDB
        vectorStore.delete(List.of(documentId.toString()));
        log.info("Document {} removed from vector store", documentId);
    }
}
