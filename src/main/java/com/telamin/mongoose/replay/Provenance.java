/*
 * SPDX-FileCopyrightText: © 2025 Gregory Higgins <greg.higgins@v12technology.com>
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.telamin.mongoose.replay;

import com.telamin.fluxtion.runtime.DataFlow;
import com.telamin.fluxtion.runtime.annotations.feature.Experimental;

/**
 * How a replay stream's processor was initialised (design "Deterministic replay"): the processor's class, the handler
 * an interpreted processor wraps, and its per-name incarnation. Recorded beside the stream when it begins, never among
 * its entries; a replay whose processor differs stops, saying how, rather than replay a stream into another program.
 */
@Experimental
public record Provenance(String processorClass, String handlerClass, int incarnation, long instant) {

    public static Provenance of(DataFlow flow, int incarnation, long instant) {
        return new Provenance(flow.getClass().getName(), handlerOf(flow), incarnation, instant);
    }

    /** What differs between this recorded provenance and {@code replayed}, or null when they match. */
    public String differenceFrom(Provenance replayed) {
        if (!processorClass.equals(replayed.processorClass)) {
            return "processor " + processorClass + " was recorded, " + replayed.processorClass + " is replayed";
        }
        if (!handlerClass.equals(replayed.handlerClass)) {
            return "handler " + handlerClass + " was recorded, " + replayed.handlerClass + " is replayed";
        }
        return null;
    }

    /** The handler an interpreted processor wraps; empty for a generated one, whose class is the program. */
    private static String handlerOf(DataFlow flow) {
        for (Class<?> c = flow.getClass(); c != null && c != Object.class; c = c.getSuperclass()) {
            try {
                java.lang.reflect.Field f = c.getDeclaredField("allEventHandler");
                f.setAccessible(true);
                Object handler = f.get(flow);
                return handler == null ? "" : handler.getClass().getName();
            } catch (NoSuchFieldException next) {
                // not declared here
            } catch (ReflectiveOperationException | RuntimeException inaccessible) {
                return "";
            }
        }
        return "";
    }
}
