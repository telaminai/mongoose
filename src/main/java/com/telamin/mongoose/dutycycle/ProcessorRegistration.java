package com.telamin.mongoose.dutycycle;

/**
 * Identity of one accepted processor add (review of #69, design "Registrations and admission"). A stop carries it, so
 * it can only address that add, never a later one registered under the same name.
 *
 * <p>The identity ({@link #generation()}, {@link #incarnation()}, {@link #name()}) is assigned once, when the add is
 * admitted, and is then immutable. {@link #startTaken} and {@link #cancelled} are read and written only on the
 * group's agent thread: they are facts the control processor decides on, written by the effects it requests.
 */
public final class ProcessorRegistration {

    private volatile long generation = -1;
    private volatile int incarnation;
    private volatile String name;
    private volatile Runnable nameReleaser;

    /**
     * Set when the group takes this add's start, whether it then starts, fails or is skipped as cancelled. A stop that
     * finds no processor cancels the registration only while this is false.
     */
    boolean startTaken;
    /** A stop overtook this start: the add is skipped when it arrives. Agent thread only. */
    boolean cancelled;

    public ProcessorRegistration() {
    }

    public ProcessorRegistration(String name) {
        this.name = name;
    }

    /** Assign the identity once; later calls keep the first assignment. */
    synchronized void assign(long generation, int incarnation, String name) {
        if (this.generation < 0) {
            this.generation = generation;
            this.incarnation = incarnation;
        }
        if (this.name == null && name != null) {
            this.name = name;
        }
    }

    synchronized boolean assigned() {
        return generation >= 0;
    }

    public long generation() {
        return generation;
    }

    public int incarnation() {
        return incarnation;
    }

    public String name() {
        return name;
    }

    /** What frees this registration's name reservation; run by the control processor's {@code ReleaseName} effect. */
    public ProcessorRegistration nameReleaser(Runnable releaser) {
        this.nameReleaser = releaser;
        return this;
    }

    void releaseName() {
        Runnable r = nameReleaser;
        if (r != null) r.run();
    }

    @Override
    public String toString() {
        return "ProcessorRegistration{" + name + " gen=" + generation + " inc=" + incarnation + "}";
    }
}
