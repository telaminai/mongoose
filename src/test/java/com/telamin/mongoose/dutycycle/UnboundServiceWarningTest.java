package com.telamin.mongoose.dutycycle;

import com.telamin.fluxtion.runtime.annotations.runtime.ServiceRegistered;
import com.telamin.fluxtion.runtime.node.ObjectEventHandlerNode;
import com.telamin.mongoose.MongooseEventHandler;
import com.telamin.mongoose.MongooseServer;
import com.telamin.mongoose.config.MongooseServerConfig;
import org.agrona.concurrent.Agent;
import org.agrona.concurrent.SleepingMillisIdleStrategy;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * fluxtionauditlog-analyser#126: a processor whose service binding no registered service satisfies is named in one
 * WARN at startup, and a satisfied one, or a processor with no bindings, is not. Each case boots a real server, so
 * the check runs where it runs in production and against the services the server itself adds.
 */
class UnboundServiceWarningTest {

    private static final String PROCESSOR = "demoProcessor";
    private static final String DEMO_PRICER = DemoPricer.class.getCanonicalName();

    private final Logger agentLog = Logger.getLogger(ComposingEventProcessorAgent.class.getName());
    private final List<LogRecord> records = new CopyOnWriteArrayList<>();
    private final Handler capture = new Handler() {
        @Override
        public void publish(LogRecord record) {
            records.add(record);
        }

        @Override
        public void flush() {
        }

        @Override
        public void close() {
        }
    };
    private MongooseServer server;

    @BeforeEach
    void captureAgentLog() {
        agentLog.addHandler(capture);
    }

    @AfterEach
    void stop() {
        agentLog.removeHandler(capture);
        if (server != null) server.stop();
    }

    @Test
    void a_binding_named_as_the_service_is_registered_logs_no_warning() {
        NamedBinding node = new NamedBinding();
        boot(node, cfg -> cfg.addService(new DemoPricerImpl(), DemoPricer.class, "demoPricer"));

        assertTrue(node.received, "witness: the binding was satisfied, the callback fired");
        assertEquals(List.of(), unboundWarnings());
    }

    @Test
    void an_unnamed_binding_against_a_named_service_logs_one_warning_naming_both() {
        UnnamedBinding node = new UnnamedBinding();
        boot(node, cfg -> cfg.addService(new DemoPricerImpl(), DemoPricer.class, "demoPricer"));

        assertFalse(node.received, "witness: the runtime did not deliver the service");
        List<String> warnings = unboundWarnings();
        assertEquals(1, warnings.size(), warnings.toString());
        String warning = warnings.get(0);
        assertTrue(warning.contains("processor '" + PROCESSOR + "'"), warning);
        assertTrue(warning.contains("named '" + DEMO_PRICER + "'"), warning);
        assertTrue(warning.contains("an unnamed @ServiceRegistered binds by the class name"), warning);
        assertTrue(warning.contains("Registered as " + DEMO_PRICER + ": [demoPricer]"), warning);
    }

    @Test
    void a_binding_named_differently_from_the_service_logs_one_warning_naming_both() {
        NamedBinding node = new NamedBinding();
        boot(node, cfg -> cfg.addService(new DemoPricerImpl(), DemoPricer.class, "pricer"));

        assertFalse(node.received, "witness: the runtime did not deliver the service");
        List<String> warnings = unboundWarnings();
        assertEquals(1, warnings.size(), warnings.toString());
        String warning = warnings.get(0);
        assertTrue(warning.contains("named 'demoPricer'"), warning);
        assertTrue(warning.contains("[pricer]"), warning);
        assertTrue(warning.contains("register the service as 'demoPricer'"), warning);
    }

    @Test
    void a_binding_with_no_service_of_its_type_lists_the_registered_services() {
        NamedBinding node = new NamedBinding();
        boot(node, cfg -> { });

        List<String> warnings = unboundWarnings();
        assertEquals(1, warnings.size(), warnings.toString());
        assertTrue(warnings.get(0).contains("No service of that type is registered; registered services: ["), warnings.get(0));
    }

    @Test
    void a_processor_with_no_service_bindings_logs_no_warning() {
        // no feed is configured either: the runtime's own feed listener, which every processor carries, must not warn
        boot(new NoBinding(), cfg -> cfg.addService(new DemoPricerImpl(), DemoPricer.class, "demoPricer"));

        assertEquals(List.of(), unboundWarnings());
    }

    @Test
    void an_agent_hosted_service_is_known_before_the_processor_joins() {
        // timing witness: a worker service is registered on its own agent thread; processor groups start after it
        NamedBinding node = new NamedBinding();
        boot(node, cfg -> cfg.addWorkerService(new DemoPricerAgent(), DemoPricer.class, "demoPricer",
                "demoServiceAgent", new SleepingMillisIdleStrategy(1)));

        assertTrue(node.received, "witness: the worker service reached the processor");
        assertEquals(List.of(), unboundWarnings());
    }

    private void boot(ObjectEventHandlerNode node, java.util.function.Consumer<MongooseServerConfig> services) {
        MongooseServerConfig cfg = new MongooseServerConfig();
        services.accept(cfg);
        cfg.addProcessor("demoGroup", new MongooseEventHandler(node), PROCESSOR);
        // bootServer returns once every processor group is ACTIVE, and a group joins its processors before that
        server = MongooseServer.bootServer(cfg);
    }

    private List<String> unboundWarnings() {
        return records.stream()
                .filter(r -> r.getLevel() == Level.WARNING)
                .map(LogRecord::getMessage)
                .filter(m -> m.startsWith("processor '" + PROCESSOR + "'"))
                .toList();
    }

    public static class DemoPricerImpl implements DemoPricer {
        @Override
        public double price(String symbol) {
            return 1.0;
        }
    }

    public static class DemoPricerAgent extends DemoPricerImpl implements Agent {
        @Override
        public int doWork() {
            return 0;
        }

        @Override
        public String roleName() {
            return "demoPricerAgent";
        }
    }

    public static class NamedBinding extends ObjectEventHandlerNode {
        volatile boolean received;

        @ServiceRegistered("demoPricer")
        public void pricer(DemoPricer pricer) {
            received = true;
        }
    }

    public static class UnnamedBinding extends ObjectEventHandlerNode {
        volatile boolean received;

        @ServiceRegistered
        public void pricer(DemoPricer pricer) {
            received = true;
        }
    }

    public static class NoBinding extends ObjectEventHandlerNode {
    }
}

/**
 * Top level, not nested: {@code ServiceConfig.toService()} resolves the service class by {@code Class.forName} of its
 * canonical name, which is not the binary name of a nested type.
 */
interface DemoPricer {
    double price(String symbol);
}
