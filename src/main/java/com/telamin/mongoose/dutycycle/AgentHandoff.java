package com.telamin.mongoose.dutycycle;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Work handed to an agent thread (mongoose#46) that runs AT MOST ONCE and never after its caller stopped waiting.
 * Whichever side claims it first decides it: the agent thread runs it, or the caller's timeout cancels it. Without the
 * claim, work a caller gave up on still ran later (an audit {@code start} that threw on timeout installed its listener
 * anyway), and work queued as its thread exited could be run twice or never.
 */
public final class AgentHandoff {

    private final AtomicBoolean claimed = new AtomicBoolean();
    private final CompletableFuture<Void> done = new CompletableFuture<>();
    private final Runnable work;

    private AgentHandoff(Runnable work) {
        this.work = work;
    }

    /** Hand {@code work} to {@code agentThread}; the returned handoff is awaited, or cancelled, by the caller. */
    public static AgentHandoff submit(Executor agentThread, Runnable work) {
        AgentHandoff handoff = new AgentHandoff(work);
        agentThread.execute(handoff::runOnce);
        return handoff;
    }

    /** Runs {@code work} unless already claimed (run, or cancelled by a caller that stopped waiting). */
    public static Runnable once(Runnable work) {
        return new AgentHandoff(work)::runOnce;
    }

    void runOnce() {
        if (!claimed.compareAndSet(false, true)) return;
        try {
            work.run();
            done.complete(null);
        } catch (Throwable t) {
            done.completeExceptionally(t);
        }
    }

    /**
     * Wait for the work. On timeout it is cancelled, if it has not started, and the caller is told so; if it had already
     * started, the wait continues until it finishes, so the caller never returns with it half applied.
     *
     * @return true when the work ran, false when it was cancelled unrun
     */
    public boolean await(long timeout, TimeUnit unit) throws InterruptedException, java.util.concurrent.ExecutionException {
        try {
            done.get(timeout, unit);
            return true;
        } catch (TimeoutException notYet) {
            if (claimed.compareAndSet(false, true)) {
                return false;                           // cancelled: it will never run
            }
            done.get();                                 // it is running on the agent thread: let it finish
            return true;
        }
    }
}
