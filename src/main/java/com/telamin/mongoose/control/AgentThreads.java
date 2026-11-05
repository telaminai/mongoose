/*
 * SPDX-FileCopyrightText: © 2025 Gregory Higgins <greg.higgins@v12technology.com>
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.telamin.mongoose.control;

/**
 * Which threads are agent threads (design "Agent threads and replies"). Each agent group marks its thread in
 * {@code onStart} and clears it after {@code onClose}. Unlike comparing with {@code AgentRunner.thread()}, which
 * Agrona replaces with a tombstone at the start of {@code close()}, the mark holds through {@code onClose}, so code a
 * group runs while closing is still recognised as agent code that must never block.
 */
public final class AgentThreads {

    private static final ThreadLocal<Object> OWNER = new ThreadLocal<>();

    private AgentThreads() {
    }

    /** Mark the current thread as an agent thread of {@code owner} (a server, or the group itself without one). */
    public static void enter(Object owner) {
        OWNER.set(owner);
    }

    /** Clear the mark; called by the group after its {@code onClose}, on its own thread. */
    public static void exit() {
        OWNER.remove();
    }

    /** True on any agent thread: such a thread never waits for a reply and never backs off. */
    public static boolean isAgentThread() {
        return OWNER.get() != null;
    }

    /** True on an agent thread of {@code owner}. */
    public static boolean isAgentOf(Object owner) {
        Object current = OWNER.get();
        return current != null && current == owner;
    }
}
