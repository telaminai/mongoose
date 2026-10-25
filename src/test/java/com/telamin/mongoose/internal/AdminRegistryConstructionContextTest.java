package com.telamin.mongoose.internal;

import com.telamin.fluxtion.runtime.DataFlow;
import com.telamin.mongoose.dispatch.ProcessorContext;
import com.telamin.mongoose.service.admin.impl.AdminCommandProcessor;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class AdminRegistryConstructionContextTest {
    @Test void builtinsCanBeConstructedInsideAProcessorBeforeServiceInjection() {
        DataFlow owner = event -> { };
        DataFlow previous = ProcessorContext.currentProcessor();
        ProcessorContext.setCurrentProcessor(owner);
        try {
            var registry = assertDoesNotThrow(AdminCommandProcessor::new);
            for (String name : new String[]{"help", "?", "commands", "eventSources"}) {
                assertNotNull(registry.registeredCommand(name), name);
            }
            assertSame(owner, ProcessorContext.currentProcessor(), "construction must preserve the caller's context");
        } finally {
            if (previous == null) ProcessorContext.removeCurrentProcessor();
            else ProcessorContext.setCurrentProcessor(previous);
        }
    }
}
