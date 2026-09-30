package com.telamin.mongoose.replay.generated.builder;

import com.telamin.fluxtion.builder.compile.config.FluxtionGraphBuilder;
import com.telamin.fluxtion.builder.generation.config.EventProcessorConfig;
import com.telamin.fluxtion.builder.compile.config.FluxtionCompilerConfig;
import com.telamin.mongoose.replay.generated.AlarmNodes;

/** Generates AlarmProcessor: the DEMO alarm graph with audit logging. */
public class AlarmProcessorBuilder implements FluxtionGraphBuilder {

    @Override
    public void buildGraph(EventProcessorConfig cfg) {
        AlarmNodes.AlarmMonitor monitor = new AlarmNodes.AlarmMonitor();
        cfg.addNode(monitor, "alarmMonitor");
        cfg.addNode(new AlarmNodes.AlarmPublisher(monitor), "alarmPublisher");
        cfg.addEventAudit();
    }

    @Override
    public void configureGeneration(FluxtionCompilerConfig cfg) {
        cfg.setClassName("AlarmProcessor");
        cfg.setPackageName("com.telamin.mongoose.replay.generated");
        cfg.setOutputDirectory("src/main/java");
    }
}
