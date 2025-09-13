/*
 * SPDX-FileCopyrightText: © 2025 Gregory Higgins <greg.higgins@v12technology.com>
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package com.telamin.mongoose.connector.file;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

class FileMessageSinkTest {

    private static final boolean TEST_KEEP_FILES = Boolean.getBoolean("TEST_KEEP_FILES");

    @TempDir
    Path tempDir;

    private Path outputFile;

    // Expose a public write method to call the protected sendToSink for testing
    static class TestableFileMessageSink extends FileMessageSink {
        public void write(Object value) {
            super.sendToSink(value);
        }
    }

    @Test
    void writesLinesToFile_andCleansUp() throws IOException {
        // Arrange
        outputFile = tempDir.resolve("sink").resolve("out.log");
        Files.createDirectories(outputFile.getParent());

        TestableFileMessageSink sink = new TestableFileMessageSink();
        sink.setFilename(outputFile.toString());

        // Act
        sink.init();
        sink.start();
        sink.write("hello");
        sink.write("world");
        sink.stop(); // flush and close

        // Assert file content
        List<String> lines = Files.readAllLines(outputFile, StandardCharsets.UTF_8);
        Assertions.assertEquals(List.of("hello", "world"), lines);

        // Cleanup files (unless user asked to keep them)
        cleanupIfNeeded();
    }

    private void cleanupIfNeeded() {
        if (TEST_KEEP_FILES) {
            System.out.println("TEST_KEEP_FILES=true; keeping artifacts at: " + tempDir);
            return;
        }
        deleteQuietly(outputFile);
        // try remove parent dir if empty
        Path parent = outputFile != null ? outputFile.getParent() : null;
        if (parent != null) {
            try {
                Files.delete(parent);
            } catch (IOException ignored) {
            }
        }
    }

    private static void deleteQuietly(Path p) {
        if (p == null) return;
        try {
            Files.deleteIfExists(p);
        } catch (IOException ignored) {
        }
    }
}
