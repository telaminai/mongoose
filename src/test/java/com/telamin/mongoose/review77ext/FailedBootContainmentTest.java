package com.telamin.mongoose.review77ext;

import com.telamin.fluxtion.runtime.lifecycle.Lifecycle;
import com.telamin.mongoose.MongooseServer;
import com.telamin.mongoose.config.MongooseServerConfig;
import com.telamin.mongoose.config.EventFeedConfig;
import com.telamin.mongoose.connector.memory.InMemoryEventSource;
import org.agrona.concurrent.SleepingMillisIdleStrategy;
import org.junit.jupiter.api.Test;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.concurrent.TimeUnit;
import static org.junit.jupiter.api.Assertions.*;

public class FailedBootContainmentTest {
    public static class FailingCompletion implements Lifecycle {
        public void init() {}
        public void start() {}
        public void stop() {}
        public void tearDown() {}
        public void startComplete() { throw new IllegalStateException("review-boot-failure"); }
    }
    public static class FailingWorker extends FailingCompletion implements org.agrona.concurrent.Agent {
        public int doWork() { return 0; }
        public String roleName() { return "failing-worker"; }
        @Override public void start() { throw new IllegalStateException("worker-start-failure"); }
    }
    public static class BootMain {
        public static void main(String[] ignored) {
            var config = MongooseServerConfig.builder().startupTimeoutMillis(1000)
                .addEventFeed(EventFeedConfig.builder().instance(new InMemoryEventSource<>())
                    .name("review-feed").broadcast(true)
                    .agent("review-zombie-agent", new SleepingMillisIdleStrategy(1)).build()).build();
            config.setShutdownTimeoutMillis(500);
            if (ignored.length > 0) {
                config.addWorkerService(new FailingWorker(), Lifecycle.class, "failing-worker",
                    "failing-worker-agent", new SleepingMillisIdleStrategy(1));
            } else config.addService(new FailingCompletion(), Lifecycle.class, "failing-completion");
            try { MongooseServer.bootServer(config, record -> {}); }
            catch (Throwable expected) { System.out.println("BOOT_FAILED: " + expected); }
            System.out.println("MAIN_RETURNED");
        }
    }
    @Test void failedBootDoesNotLeaveUnownedNonDaemonAgents() throws Exception { check(false); }
    @Test void failedAgentBootDoesNotLeaveUnownedNonDaemonAgents() throws Exception { check(true); }
    void check(boolean agentFailure) throws Exception {
        var args = new ArrayList<String>();
        args.add(System.getProperty("java.home") + "/bin/java");
        args.addAll(java.lang.management.ManagementFactory.getRuntimeMXBean().getInputArguments().stream()
            .filter(a -> a.startsWith("--add-") || a.startsWith("-Dchronicle")).toList());
        args.add("-cp");
        args.add(System.getProperty("surefire.test.class.path", System.getProperty("java.class.path")));
        args.add(BootMain.class.getName());
        if (agentFailure) args.add("worker");
        var log = Files.createTempFile("review77-boot-", ".log");
        var child = new ProcessBuilder(args).redirectErrorStream(true).redirectOutput(log.toFile()).start();
        try {
            boolean exited = child.waitFor(6, TimeUnit.SECONDS);
            String output = Files.readString(log);
            if (agentFailure && Boolean.getBoolean("review.expectBootExit")) {
                assertTrue(exited, "old EXIT_PROCESS policy must terminate the child");
                assertEquals(255, child.exitValue(), output);
                return;
            }
            assertTrue(output.contains("BOOT_FAILED:") && output.contains("MAIN_RETURNED"), output);
            assertTrue(exited, "bootServer threw without returning ownership, but non-daemon agents kept JVM alive. " + log);
        } finally {
            child.destroyForcibly();
            child.waitFor(3, TimeUnit.SECONDS);
        }
    }
}

