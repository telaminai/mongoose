package com.telamin.mongoose.replay;

import com.telamin.fluxtion.runtime.annotations.runtime.ServiceRegistered;
import com.telamin.fluxtion.runtime.node.ObjectEventHandlerNode;
import com.telamin.fluxtion.runtime.output.MessageSink;
import com.telamin.mongoose.service.admin.AdminCommandRegistry;
import com.telamin.mongoose.service.scheduler.SchedulerService;

/**
 * Every kind of input a Mongoose processor receives (spec-replay-recording §3), in one DEMO processor:
 * orders from one feed, controls from another, a breach the graph raises itself on the second live order, a timeout
 * armed by a control, a processor-owned admin command, and an order that throws. Each line carries the instant the
 * processor read, so a replay that differs in time shows.
 */
public class ReplayDemoHandler extends ObjectEventHandlerNode {

    public record Breach(String orderId, int live) implements java.io.Serializable { }

    private final String orders, controls;
    private MessageSink<String> sink;
    private SchedulerService scheduler;
    private int live;
    private boolean suspended;

    public ReplayDemoHandler(String orders, String controls) {
        this.orders = orders;
        this.controls = controls;
    }

    @ServiceRegistered
    public void sink(MessageSink<String> sink, String name) {
        this.sink = sink;
    }

    @ServiceRegistered
    public void scheduler(SchedulerService scheduler, String name) {
        this.scheduler = scheduler;
    }

    @ServiceRegistered
    public void admin(AdminCommandRegistry registry, String name) {
        registry.registerCommand("quotes.reset", (args, out, err) -> {
            live = 0;
            emit("reset by=" + args + " time=" + getContext().getClock().getWallClockTime());
            out.accept("reset");
        });
    }

    @Override
    public void start() {
        getContext().subscribeToNamedFeed(orders);
        getContext().subscribeToNamedFeed(controls);
    }

    @Override
    protected boolean handleEvent(Object event) {
        long time = getContext().getClock().getProcessTime();
        if (event instanceof String s && s.equals("boom")) {
            throw new IllegalStateException("DEMO failure on " + s);
        } else if (event instanceof String s && s.startsWith("ord-")) {
            live++;
            emit("order=" + s + " live=" + live + " suspended=" + suspended + " time=" + time);
            if (live == 2) getContext().getParentDataFlow().onEvent(new Breach(s, live));
        } else if (event instanceof Breach b) {
            emit("breach=" + b + " time=" + time);
        } else if (event instanceof String s && (s.equals("suspend") || s.equals("resume"))) {
            suspended = s.equals("suspend");
            emit("control=" + s + " time=" + time);
        } else if (event instanceof String s && s.equals("arm")) {
            long armedAt = time;
            emit("armed time=" + time);
            scheduler.scheduleAfterDelay(40, () -> emit("timeout armedAt=" + armedAt + " live=" + live
                    + " time=" + getContext().getClock().getWallClockTime()));
        }
        return true;
    }

    private void emit(String line) {
        if (sink != null) sink.accept(line);
    }
}
