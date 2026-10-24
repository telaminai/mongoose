package com.telamin.mongoose.control;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins the generated control processor's shape that the effect model relies on (design "Reference" and "Effects and
 * outcomes"; review 4 findings 1 and 4), so a regeneration with another builder or generator option fails here, not
 * at runtime:
 * <ul>
 *   <li>re-entrancy is on: an event arriving while {@code processing} is queued, not thrown;</li>
 *   <li>{@code batchEnd()} performs the requested effects before it publishes its record and dispatches the queue;</li>
 *   <li>an effect result reaches the outcome recorder before any rule;</li>
 *   <li>every node class is in the design's {@code nodeBeans}: a bean in no list silently drops out of the graph.</li>
 * </ul>
 */
class ControlProcessorShapeTest {

    private static final Path GENERATED = Path.of(
            "src/main/java/com/telamin/mongoose/control/generated/GroupControlProcessor.java");
    private static final Path DESIGN = Path.of("src/main/fluxtion/control/control-processor.xml");
    private static final Path NODES = Path.of("src/main/java/com/telamin/mongoose/control/node");

    private static String squash(String s) {
        return s.replaceAll("\\s+", " ");
    }

    @Test
    void reentrantEventsAreQueuedWhileProcessing() throws IOException {
        String src = squash(Files.readString(GENERATED));
        assertTrue(src.contains("if (processing) { callbackDispatcher.queueReentrantEvent(event); }"),
                "the generated onEvent must queue an event arriving while processing (supportReentrancy on)");
    }

    @Test
    void batchEndPerformsEffectsBeforePublishingAndDispatching() throws IOException {
        String src = squash(Files.readString(GENERATED));
        Matcher m = Pattern.compile("public void batchEnd\\(\\) \\{(.*?)\\}").matcher(src);
        assertTrue(m.find(), "batchEnd() not found");
        String body = m.group(1);
        int perform = body.indexOf("effectQueue.performRequestedEffects();");
        int after = body.indexOf("afterEvent();");
        int dispatch = body.indexOf("callbackDispatcher.dispatchQueuedCallbacks();");
        assertTrue(body.indexOf("processing = true;") >= 0 && perform > body.indexOf("processing = true;")
                && after > perform && dispatch > after, "unexpected batchEnd() shape: " + body);
    }

    @Test
    void effectResultsAreRecordedBeforeAnyRuleReacts() throws IOException {
        String src = squash(Files.readString(GENERATED));
        Matcher m = Pattern.compile("public void handleEvent\\(EffectResult typedEvent\\) \\{(.*?)afterEvent\\(\\);")
                .matcher(src);
        assertTrue(m.find(), "EffectResult dispatch not found");
        String body = m.group(1);
        int outcomes = body.indexOf("effectOutcomes.onResult");
        assertTrue(outcomes >= 0 && outcomes < body.indexOf("lifecycleRules.onResult")
                && outcomes < body.indexOf("serviceRules.onResult"), "outcome recorder must run first: " + body);
    }

    @Test
    void everyNodeClassIsDeclaredInTheDesign() throws IOException {
        String design = Files.readString(DESIGN);
        try (Stream<Path> files = Files.list(NODES)) {
            files.map(p -> p.getFileName().toString())
                    .filter(f -> f.endsWith(".java") && !f.equals("ControlAudit.java"))
                    .map(f -> f.substring(0, f.length() - 5))
                    .forEach(cls -> {
                        String bean = Character.toLowerCase(cls.charAt(0)) + cls.substring(1);
                        assertTrue(design.contains("class=\"com.telamin.mongoose.control.node." + cls + "\""),
                                cls + " has no bean in the design");
                        assertTrue(design.contains("<value>" + bean + "</value>"), bean + " is not in nodeBeans");
                    });
        }
    }

    @Test
    void generatedSourceCarriesTheProjectLicenceNotAProprietaryNotice() throws IOException {
        String src = Files.readString(GENERATED);
        assertTrue(src.startsWith("/*\n * SPDX-FileCopyrightText"), "generated header not replaced");
        assertEquals(-1, src.indexOf("confidential"), "proprietary notice left in the generated source");
    }
}
