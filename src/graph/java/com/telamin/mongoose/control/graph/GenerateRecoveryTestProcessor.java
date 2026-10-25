package com.telamin.mongoose.control.graph;

import com.telamin.fluxtion.builder.extern.spring.FluxtionSpring;
import java.nio.file.Files;
import java.nio.file.Path;

/** Test graph regeneration, after its ordinary application node has been test-compiled. */
public final class GenerateRecoveryTestProcessor {
    public static void main(String[] args) throws Exception {
        Path base = Path.of(args[0]).toAbsolutePath();
        FluxtionSpring.compileAot(base.resolve("src/test/fluxtion/recovery-processor.xml"), cfg -> {
            cfg.setClassName("RecoveryClientProcessor");
            cfg.setPackageName("com.telamin.mongoose.recovery.generated");
            cfg.setOutputDirectory(base.resolve("src/test/java").toString());
            cfg.setResourcesOutputDirectory(base.resolve("src/test/resources").toString());
            cfg.setWriteSourceToFile(true);
            cfg.setFormatSource(true);
            cfg.setCopySourceToResourcesDirectory(false);
        });
        Path generated = base.resolve("src/test/java/com/telamin/mongoose/recovery/generated/RecoveryClientProcessor.java");
        String source = Files.readString(generated);
        int pkg = source.indexOf("package ");
        if (pkg < 0) throw new IllegalStateException("no package declaration: " + generated);
        Files.writeString(generated, """
                /*
                 * SPDX-FileCopyrightText: © 2026 Gregory Higgins <greg.higgins@v12technology.com>
                 * SPDX-License-Identifier: AGPL-3.0-only
                 * GENERATED from src/test/fluxtion/recovery-processor.xml.
                 * Regenerate with: mvn -o -Pregen process-test-classes
                 */
                """ + source.substring(pkg));
    }
}
