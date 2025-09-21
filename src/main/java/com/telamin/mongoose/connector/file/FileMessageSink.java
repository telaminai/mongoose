/*
 * SPDX-FileCopyrightText: © 2025 Gregory Higgins <greg.higgins@v12technology.com>
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package com.telamin.mongoose.connector.file;

import com.telamin.fluxtion.runtime.lifecycle.Lifecycle;
import com.telamin.fluxtion.runtime.output.AbstractMessageSink;
import lombok.Getter;
import lombok.Setter;
import lombok.SneakyThrows;
import lombok.extern.java.Log;

import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;

@Log
public class FileMessageSink extends AbstractMessageSink<Object>
        implements Lifecycle {

    @Getter
    @Setter
    private String filename;
    private PrintStream printStream;

    @Override
    public void init() {
    }

    @SneakyThrows
    @Override
    public void start() {
        Path path = Paths.get(filename);
        path.toFile().getParentFile().mkdirs();
        printStream = new PrintStream(
                Files.newOutputStream(path, StandardOpenOption.CREATE, StandardOpenOption.APPEND),
                false,
                StandardCharsets.UTF_8
        );
    }

    @Override
    protected void sendToSink(Object value) {
        log.finer(() -> "sink publish:" + value);
        printStream.println(value);
    }

    @Override
    public void stop() {
        printStream.flush();
        printStream.close();
    }

    @Override
    public void tearDown() {
        stop();
    }
}
