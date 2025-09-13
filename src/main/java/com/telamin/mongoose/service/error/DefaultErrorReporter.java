/*
 * SPDX-FileCopyrightText: © 2025 Gregory Higgins <greg.higgins@v12technology.com>
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.telamin.mongoose.service.error;

import lombok.extern.java.Log;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.logging.Level;

/**
 * Default in-memory error reporter that logs and notifies listeners.
 */
@Log
public class DefaultErrorReporter implements ErrorReporter {
    private final CopyOnWriteArrayList<ErrorListener> listeners = new CopyOnWriteArrayList<>();
    private final ArrayDeque<ErrorEvent> ring = new ArrayDeque<>(128);
    private final int capacity;

    public DefaultErrorReporter() {
        this(100);
    }

    public DefaultErrorReporter(int capacity) {
        this.capacity = Math.max(1, capacity);
    }

    @Override
    public void addListener(ErrorListener listener) {
        if (listener != null) listeners.addIfAbsent(listener);
    }

    @Override
    public void removeListener(ErrorListener listener) {
        if (listener != null) listeners.remove(listener);
    }

    @Override
    public void report(ErrorEvent event) {
        if (event == null) return;
        // keep a bounded history
        synchronized (ring) {
            if (ring.size() >= capacity) {
                ring.removeFirst();
            }
            ring.addLast(event);
        }
        // log
        Level level = switch (event.getSeverity()) {
            case INFO -> Level.INFO;
            case WARNING -> Level.WARNING;
            case ERROR, CRITICAL -> Level.SEVERE;
        };
        if (event.getError() != null) {
            log.log(level, event.getSource() + ": " + event.getMessage(), event.getError());
        } else {
            log.log(level, event.getSource() + ": " + event.getMessage());
        }
        // notify
        for (ErrorListener l : listeners) {
            try {
                l.onError(event);
            } catch (Throwable t) {
                log.log(Level.WARNING, "error listener threw exception: " + l + ", error=" + t, t);
            }
        }
    }

    @Override
    public List<ErrorEvent> recent(int limit) {
        List<ErrorEvent> list = new ArrayList<>();
        if (limit <= 0) return list;
        synchronized (ring) {
            int size = ring.size();
            int start = Math.max(0, size - limit);
            int i = 0;
            for (ErrorEvent e : ring) {
                if (i++ >= start) list.add(e);
            }
        }
        return list;
    }
}
