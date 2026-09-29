package com.telamin.mongoose.replay.generated;

import com.telamin.fluxtion.runtime.annotations.OnEventHandler;
import com.telamin.fluxtion.runtime.annotations.OnTrigger;
import com.telamin.fluxtion.runtime.annotations.Start;
import com.telamin.fluxtion.runtime.annotations.runtime.ServiceRegistered;
import com.telamin.fluxtion.runtime.event.Signal;
import com.telamin.fluxtion.runtime.node.BaseNode;
import com.telamin.mongoose.service.admin.AdminCommandRegistry;
import com.telamin.mongoose.service.admin.AdminCommandRequest;

/**
 * DEMO nodes for the generated AlarmProcessor (spec-replay-recording 3d): an admin command as a signal handler in the
 * graph (option A), a lambda admin command as today, and a downstream node that reacts when the monitor changes.
 */
public final class AlarmNodes {

    private AlarmNodes() { }

    /** A reading from the DEMO feed. */
    public record Reading(double value) implements java.io.Serializable { }

    /** Raises an alarm when a reading passes the limit. */
    public static class AlarmMonitor extends BaseNode {
        public double limit = 10;
        private boolean raised;

        @ServiceRegistered
        public void admin(AdminCommandRegistry registry, String name) {
            // option A: the command is this node's signal handler below, in the processor's event cycle
            registry.registerSignalCommand("alarm.reset");
            // today's form: a lambda, run on the processor's thread outside any event cycle
            registry.registerCommand("alarm.lambda", (args, out, err) -> {
                raised = false;
                auditLog.info("lambdaReset", true);
                out.accept("lambda cleared");
            });
        }

        @Start
        public void start() {
            context.subscribeToNamedFeed("readings");
        }

        @OnEventHandler
        public boolean onReading(Reading reading) {
            boolean was = raised;
            raised = reading.value() > limit;
            auditLog.info("value", reading.value()).info("raised", raised);
            return was != raised;
        }

        @OnEventHandler(filterString = "admin:alarm.reset")
        public boolean reset(Signal<AdminCommandRequest> signal) {
            boolean was = raised;
            raised = false;
            auditLog.info("reset", true).info("resetBy", String.valueOf(signal.getValue().getArguments()));
            signal.getValue().getOutput().accept("alarm cleared");
            return was;
        }

        public boolean isRaised() {
            return raised;
        }
    }

    /** Downstream: triggered when the monitor changes, so a change made by a command is seen to propagate. */
    public static class AlarmPublisher extends BaseNode {
        public AlarmMonitor monitor;
        public int changes;

        public AlarmPublisher(AlarmMonitor monitor) {
            this.monitor = monitor;
        }

        public AlarmPublisher() {
        }

        @OnTrigger
        public boolean onChange() {
            changes++;
            auditLog.info("published", monitor.isRaised()).info("changes", changes);
            return true;
        }
    }
}
