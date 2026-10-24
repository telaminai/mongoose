package com.telamin.mongoose.dutycycle;

/**
 * Identity of one accepted processor add (review of #69). A stop carries it, so it can only address that add, never a
 * later one registered under the same name. Its state is read and written only on the group's agent thread.
 */
public final class ProcessorRegistration {

    /**
     * Set when the group takes this add's start, whether it then starts, fails or is skipped as cancelled. A stop that
     * finds no processor cancels the registration only while this is false: only then can its start still arrive.
     */
    boolean startTaken;
}
