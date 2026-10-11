package com.ai_knowledge_assistant.ai_knowledge_assistant.storage;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

@Component
public class LocalDocumentStorage implements DocumentStorage {

    private final Path root;

    public LocalDocumentStorage(@Value("${storage.local.path}") String root) {
        this.root = Path.of(root);
    }

    @Override
    public void save(Long documentId, InputStream content) throws IOException {
        Files.createDirectories(root);
        Files.copy(content, pathFor(documentId), StandardCopyOption.REPLACE_EXISTING);
    }

    @Override
    public InputStream open(Long documentId) throws IOException {
        return Files.newInputStream(pathFor(documentId));
    }

    @Override
    public void delete(Long documentId) throws IOException {
        Files.deleteIfExists(pathFor(documentId));
    }

    // Files are named by document ID only, so user-supplied filenames never reach the filesystem
    private Path pathFor(Long documentId) {
        return root.resolve(documentId.toString());
    }
}
