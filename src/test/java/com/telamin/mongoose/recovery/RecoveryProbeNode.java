package com.telamin.mongoose.recovery;

import com.telamin.fluxtion.runtime.annotations.OnEventHandler;
import com.telamin.fluxtion.runtime.annotations.builder.Inject;
import com.telamin.fluxtion.runtime.audit.EventLogNode;
import com.telamin.fluxtion.runtime.context.DataFlowContext;
import java.util.ArrayList;
import java.util.List;

/** An application-style node: its first effect is deliberately not reversible. */
public class RecoveryProbeNode extends EventLogNode {
    public record Input(int id) { }
    @Inject private DataFlowContext context;
    public final transient List<Integer> effects = new ArrayList<>();

    public void setContext(DataFlowContext context) {
        this.context = context;
    }

    @OnEventHandler
    public boolean onInput(Input input) {
        effects.add(input.id());
        auditLog.info("inputId", input.id());
        if (input.id() == 1) {
            context.getEventDispatcher().processAsNewEventCycle(new Input(99));
            throw new IllegalStateException("client failure after effect 1");
        }
        return true;
    }
}
