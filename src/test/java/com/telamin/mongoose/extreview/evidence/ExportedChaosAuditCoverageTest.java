package com.telamin.mongoose.extreview.evidence;

import com.telamin.mongoose.control.ControlAuditExport;
import com.telamin.mongoose.control.validation.ControlEvidence;
import com.telamin.mongoose.control.validation.ControlInvariants;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * External review: re-verify the bundled chaos export (seed 19796) with the project's own validator, and measure how
 * often each invariant could actually bite in it. Runs only when the archive has been extracted to
 * {@code -Dextreview.auditDir} (default target/review-extract).
 */
class ExportedChaosAuditCoverageTest {

    @Test
    void reverifyAndMeasureCoverage() throws Exception {
        Path dir = Path.of(System.getProperty("extreview.auditDir", "target/review-extract"));
        Assumptions.assumeTrue(Files.isDirectory(dir), "no extracted export at " + dir);
        for (String g : List.of("g0", "g1", "g2")) {
            Path file = dir.resolve("chaos-" + g + "-19796.yaml");
            Assumptions.assumeTrue(Files.exists(file), "missing " + file);
            ControlAuditExport.Verification v = ControlAuditExport.verify(file);
            ControlEvidence evidence = ControlEvidence.parse(Files.readString(file), v.permitsInvariantPass());
            ControlInvariants.Result result = ControlInvariants.check(evidence);
            Map<String, Integer> kinds = new TreeMap<>();
            int closeGroupMultiRelease = 0, failedClose = 0, failedUninstall = 0, duplicate = 0, offline = 0,
                    timedOut = 0, notRunning = 0, dead = 0, anomaly = 0, failedFallback = 0, stale = 0, cleanup = 0,
                    replyCompletedFalse = 0;
            for (ControlEvidence.Rec r : evidence.records()) {
                kinds.merge(r.node() + ":" + r.type() + (r.str("rule") != null ? ":" + r.str("rule") : ""), 1, Integer::sum);
                if ("Decision".equals(r.type()) && "closeGroup".equals(r.str("rule"))
                        && r.str("effects") != null && r.str("effects").split("Release\\(").length > 2) closeGroupMultiRelease++;
                if ("EffectOutcome".equals(r.type()) && "failed".equals(r.str("outcome"))) {
                    if ("CloseCapture".equals(r.str("effect"))) failedClose++;
                    if ("UninstallCapture".equals(r.str("effect"))) failedUninstall++;
                    if ("open timed out".equals(r.str("reason"))) timedOut++;
                }
                if (Boolean.TRUE.equals(r.fields().get("duplicate"))) duplicate++;
                if (Boolean.TRUE.equals(r.fields().get("offline"))) offline++;
                if (Boolean.TRUE.equals(r.fields().get("stale"))) stale++;
                if (Boolean.TRUE.equals(r.fields().get("cleanup"))) cleanup++;
                if ("Reply".equals(r.type()) && Boolean.FALSE.equals(r.fields().get("completed"))) replyCompletedFalse++;
                if (r.fallback()) {
                    switch (r.type()) {
                        case "NotRunning", "NOT_RUNNING" -> notRunning++;
                        case "Dead" -> dead++;
                        case "Anomaly" -> anomaly++;
                        case "Failed", "FAILED" -> failedFallback++;
                        default -> { }
                    }
                }
            }
            System.out.println("EXTREVIEW " + g + " " + v + " records=" + evidence.records().size() + " verified="
                    + result.verified() + " " + result);
            System.out.println("EXTREVIEW " + g + " bite-counts: closeGroupWith>1Release=" + closeGroupMultiRelease
                    + " failedCloseCapture=" + failedClose + " failedUninstall=" + failedUninstall + " duplicate="
                    + duplicate + " offlineUninstall=" + offline + " openTimedOut=" + timedOut + " notRunningFallback="
                    + notRunning + " deadFallback=" + dead + " anomalyFallback=" + anomaly + " failedFallback="
                    + failedFallback + " stale=" + stale + " cleanupFlagged=" + cleanup + " replyCompletedFalse="
                    + replyCompletedFalse);
            System.out.println("EXTREVIEW " + g + " kinds=" + kinds);
            assertTrue(v.permitsInvariantPass(), v.toString());
            assertTrue(result.verified(), result.toString());
        }
    }
}
