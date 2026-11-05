package com.telamin.mongoose.review77ext;
import com.telamin.fluxtion.runtime.annotations.runtime.ServiceRegistered;
import com.telamin.fluxtion.runtime.node.ObjectEventHandlerNode;
import com.telamin.mongoose.MongooseServer;
import com.telamin.mongoose.config.*;
import com.telamin.mongoose.connector.memory.InMemoryEventSource;
import com.telamin.mongoose.replay.*;
import com.telamin.mongoose.control.event.FailureKind;
import com.telamin.mongoose.service.servercontrol.MongooseServerController;
import org.agrona.concurrent.SleepingMillisIdleStrategy;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
public class ReplayKindOverwriteTest {
    public static class Spawner extends ObjectEventHandlerNode {
        MongooseServerController controller;
        @ServiceRegistered public void controller(MongooseServerController c) { controller=c; }
        @Override public void start() { getContext().subscribeToNamedFeed("feed"); }
        @Override protected boolean handleEvent(Object event) {
            if ("spawn".equals(event)) controller.addEventProcessor("spawned","g",null,()-> {
                var flow=new com.telamin.mongoose.internal.ConfigAwareEventProcessor(new ObjectEventHandlerNode());
                flow.init(); return flow;
            });
            return true;
        }
    }
    record Rig(MongooseServer server, InMemoryEventSource<Object> feed) implements AutoCloseable {
        public void close() { server.stop(); }
    }
    Rig boot(ReplayConfig replay) throws Exception {
        var feed=new InMemoryEventSource<Object>();
        var cfg=MongooseServerConfig.builder()
            .addProcessorGroup(EventProcessorGroupConfig.builder().agentName("g")
                .put("p",new EventProcessorConfig(new Spawner())).build())
            .addEventFeed(EventFeedConfig.builder().instance(feed).name("feed").broadcast(true)
                .agent("feed-agent",new SleepingMillisIdleStrategy(1)).build()).replay(replay).build();
        var server=MongooseServer.bootServer(cfg,r->{}); Thread.sleep(100); return new Rig(server,feed);
    }
    @Test void identityFailureCannotBeOverwrittenByItsPropagationException() throws Exception {
        var store=new InMemoryReplayStore(); var journal=new InMemoryEventJournal();
        var codecs=Map.<String,EventCodec>of("feed",new JavaSerializationCodec());
        try(var rig=boot(ReplayConfig.record(Set.of("p"),codecs,journal,store))) {
            rig.feed().offer("spawn");
            long end=System.nanoTime()+3_000_000_000L;
            while(rig.server().registeredProcessors().get("g").stream().noneMatch(p->p.name().equals("spawned")) && System.nanoTime()<end) Thread.sleep(5);
            assertTrue(rig.server().registeredProcessors().get("g").stream().anyMatch(p->p.name().equals("spawned")));
        }
        try(var rig=boot(ReplayConfig.replay(Set.of("p"),codecs,journal,store))) {
            var replay=rig.server().replayers().get("g");
            long end=System.nanoTime()+3_000_000_000L;
            while(!replay.complete() && System.nanoTime()<end) Thread.sleep(5);
            assertTrue(replay.complete());
            assertTrue(replay.stopped("p").contains("supplier"));
            assertEquals(FailureKind.REPLAY_IDENTITY_MISMATCH,replay.stopKind("p"),replay.stopped("p"));
        }
    }
}

