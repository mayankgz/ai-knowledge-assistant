package com.ai_knowledge_assistant.ai_knowledge_assistant.storage;

import java.io.IOException;
import java.io.InputStream;

// Where uploaded files live between upload and indexing. Local disk for now; an
// S3 implementation can replace it without touching the upload or indexing code.
public interface DocumentStorage {

    void save(Long documentId, InputStream content) throws IOException;

    InputStream open(Long documentId) throws IOException;

    void delete(Long documentId) throws IOException;
}
