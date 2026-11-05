package com.telamin.mongoose.control;

import org.junit.jupiter.api.extension.AfterEachCallback;
import org.junit.jupiter.api.extension.ExtensionContext;

/**
 * Registered for every test (junit-platform.properties, autodetection): a test that drives a group's {@code onStart}
 * inline marks the JUnit thread as agent code, and a later test on that thread would then never wait for a reply.
 * The mark is cleared after each test, whatever the test did.
 */
public class ClearAgentMarkExtension implements AfterEachCallback {

    @Override
    public void afterEach(ExtensionContext context) {
        AgentThreads.exit();
    }
}
