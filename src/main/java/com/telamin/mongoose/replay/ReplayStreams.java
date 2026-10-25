/*
 * SPDX-FileCopyrightText: © 2025 Gregory Higgins <greg.higgins@v12technology.com>
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.telamin.mongoose.replay;

/**
 * Replay stream identity (design "Deterministic replay", decision D3): a processor's stream is keyed by its name and
 * its per-name {@code incarnation} in the group (1, 2, ... in the order its adds were accepted), never by the live
 * {@code generation}, which concurrent adds of different names race for. The first incarnation keeps the bare name, so
 * recordings made before incarnations were keyed still read as the first incarnation's stream.
 */
public final class ReplayStreams {

    private ReplayStreams() {
    }

    public static String key(String name, int incarnation) {
        return incarnation <= 1 ? name : name + "#" + incarnation;
    }
}
