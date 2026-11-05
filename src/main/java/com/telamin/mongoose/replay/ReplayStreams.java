/*
 * SPDX-FileCopyrightText: © 2025 Gregory Higgins <greg.higgins@v12technology.com>
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.telamin.mongoose.replay;

/**
 * Replay stream identity (design "Deterministic replay", decision D3): a processor's stream is keyed by its name and
 * its per-name build ordinal in the group, passed here as {@code incarnation} (1, 2, ... counting the processors of the
 * name actually built in the group, so an add refused, cancelled or never built consumes no number), never by the live
 * {@code generation}, which concurrent adds of different names race for. Names escape each {@code #} as {@code ##}
 * before the ordinal suffix is added, so a name such as
 * {@code p#2} cannot alias the second incarnation of {@code p}. The first incarnation of an ordinary name keeps
 * the bare name. Legacy recordings whose processor names contain {@code #} require key migration; accepting their
 * unescaped keys here would make stream identity ambiguous.
 */
public final class ReplayStreams {

    private ReplayStreams() {
    }

    public static String key(String name, int incarnation) {
        String escaped = name.replace("#", "##");
        return incarnation <= 1 ? escaped : escaped + "#" + incarnation;
    }
}
