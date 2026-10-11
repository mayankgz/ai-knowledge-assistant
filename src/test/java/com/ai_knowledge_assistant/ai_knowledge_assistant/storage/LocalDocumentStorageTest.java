package com.ai_knowledge_assistant.ai_knowledge_assistant.storage;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LocalDocumentStorageTest {

    // JUnit creates an empty temp folder per test and deletes it afterwards
    @TempDir
    Path tempDir;

    private Path root;
    private LocalDocumentStorage storage;

    @BeforeEach
    void setUp() {
        root = tempDir.resolve("uploads");   // doesn't exist yet: save() must create it
        storage = new LocalDocumentStorage(root.toString());
    }

    private static InputStream bytes(String s) {
        return new ByteArrayInputStream(s.getBytes(UTF_8));
    }

    private String read(Long id) throws IOException {
        try (InputStream in = storage.open(id)) {
            return new String(in.readAllBytes(), UTF_8);
        }
    }

    @Test
    void savedFileCanBeReadBack() throws IOException {
        storage.save(7L, bytes("hello"));

        assertThat(read(7L)).isEqualTo("hello");
        assertThat(root.resolve("7")).exists();   // named by document ID only
    }

    @Test
    void savingAgainReplacesTheFile() throws IOException {
        storage.save(7L, bytes("first"));
        storage.save(7L, bytes("second"));

        assertThat(read(7L)).isEqualTo("second");
    }

    @Test
    void deleteRemovesTheFileAndIsSafeToRepeat() throws IOException {
        storage.save(7L, bytes("hello"));

        storage.delete(7L);
        storage.delete(7L);   // already gone: no error

        assertThat(root.resolve("7")).doesNotExist();
    }

    @Test
    void openingAMissingFileThrowsNoSuchFile() {
        assertThatThrownBy(() -> storage.open(99L)).isInstanceOf(NoSuchFileException.class);
    }
}
