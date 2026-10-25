package com.telamin.mongoose.control;

import com.telamin.fluxtion.runtime.DataFlow;
import com.telamin.mongoose.service.audit.AuditCaptureResources;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

/**
 * Test doubles for audit capture as effects: an I/O seam that runs each open, close and timeout only when the test
 * says so, and physical resources that keep their own ledger. The verdict of a capture test comes from this ledger and
 * from the audit invariants, independently.
 */
final class CaptureRig {

    /** Runs nothing until asked: the test decides when each open completes, so late and duplicate completions are exact. */
    static final class ManualIo implements CaptureIo {
        final Deque<Runnable> opens = new ArrayDeque<>();
        /** Refuse the next open handed over, as a helper pool that is shut down would. */
        boolean rejectNextOpen;
        /** Refuse the next close handed over: the group then closes inline. */
        boolean rejectNextClose;
        final Deque<Runnable> closes = new ArrayDeque<>();
        final List<Runnable> timeouts = new ArrayList<>();
        final List<Boolean> timeoutCancelled = new ArrayList<>();
        final List<Runnable> opensRun = new ArrayList<>();

        @Override
        public void open(Runnable task) {
            if (rejectNextOpen) {
                rejectNextOpen = false;
                throw new java.util.concurrent.RejectedExecutionException("simulated: the opener pool is shut down");
            }
            opens.add(task);
        }

        @Override
        public void close(Runnable task) {
            if (rejectNextClose) {
                rejectNextClose = false;
                throw new java.util.concurrent.RejectedExecutionException("simulated: the closer is shut down");
            }
            closes.add(task);
        }

        @Override
        public Cancellable schedule(Runnable task, long millis) {
            int i = timeouts.size();
            timeouts.add(task);
            timeoutCancelled.add(false);
            return () -> timeoutCancelled.set(i, true);
        }

        /** Run the oldest open now; returns false when none is waiting. */
        boolean runOpen() {
            Runnable r = opens.poll();
            if (r == null) return false;
            opensRun.add(r);
            r.run();
            return true;
        }

        /**
         * Run an open that already ran, again: a helper that delivers its completion twice, each time with a resource
         * of its own. The second is a duplicate, and its resource must still be closed.
         */
        boolean rerunOpen(int i) {
            if (opensRun.isEmpty()) return false;
            opensRun.get(Math.floorMod(i, opensRun.size())).run();
            return true;
        }

        /** Run the open queued last (an open that overtakes an earlier one). */
        boolean runLatestOpen() {
            Runnable r = opens.pollLast();
            if (r == null) return false;
            opensRun.add(r);
            r.run();
            return true;
        }

        boolean runClose() {
            Runnable r = closes.poll();
            if (r == null) return false;
            r.run();
            return true;
        }

        /** Fire timeout {@code i} as the timer would, unless it was cancelled. */
        void fireTimeout(int i) {
            if (!timeoutCancelled.get(i)) timeouts.get(i).run();
        }

        void runAll() {
            boolean any = true;
            while (any) {
                any = runOpen() | runClose();
            }
        }
    }

    /** A capture resource with its own life history. */
    static final class Res {
        final int serial;
        final String processor;
        int closeAttempts;
        int closes;
        int closeFailures;
        final List<DataFlow> installedOn = new ArrayList<>();

        Res(int serial, String processor) {
            this.serial = serial;
            this.processor = processor;
        }

        @Override
        public String toString() {
            return "res#" + serial + "(" + processor + ")";
        }
    }

    /** Physical resources with a ledger: what was opened, installed where, uninstalled, closed and how often. */
    static final class FakeResources implements AuditCaptureResources {
        final List<Res> opened = new ArrayList<>();
        /** What is installed on each processor instance now. */
        final Map<DataFlow, Res> installed = new IdentityHashMap<>();
        boolean failOpens;
        boolean failInstalls;
        boolean failCloses;
        /** Whether an instance is running: installing on one that is not is a finding. */
        java.util.function.Predicate<DataFlow> running = flow -> true;
        /**
         * Ledger violations, recorded rather than thrown: the group catches whatever an effect throws and turns it into
         * a failed effect, so a thrown AssertionError would be compensated and never fail a test (review of PR 5, 3).
         */
        final List<String> findings = new ArrayList<>();
        boolean failUninstalls;
        boolean failLiveUninstalls;
        final java.util.Set<Long> failedUninstallResources = new java.util.HashSet<>();

        @Override
        public Object openCapture(String processorName) {
            if (failOpens) throw new IllegalStateException("simulated open failure of " + processorName);
            Res r = new Res(opened.size() + 1, processorName);
            opened.add(r);
            return r;
        }

        @Override
        public void installCapture(Object resource, String processorName, DataFlow dataFlow) {
            if (failInstalls) throw new IllegalStateException("simulated install failure");
            Res r = (Res) resource;
            if (r.closes > 0) findings.add(r + " installed after it was closed");
            if (!running.test(dataFlow)) findings.add(r + " installed on " + dataFlow + ", which is not running");
            Res other = installed.put(dataFlow, r);
            if (other != null) findings.add(r + " installed over " + other + " on " + dataFlow);
            r.installedOn.add(dataFlow);
        }

        @Override
        public void uninstallCapture(Object resource, String processorName, DataFlow dataFlow) {
            if (failUninstalls || (failLiveUninstalls && dataFlow != null)) {
                failedUninstallResources.add((long) ((Res) resource).serial);
                throw new IllegalStateException("simulated uninstall failure");
            }
            Res r = (Res) resource;
            if (dataFlow != null) installed.remove(dataFlow, r);
            else installed.values().removeIf(x -> x == r);
        }

        @Override
        public void closeCapture(Object resource) {
            Res r = (Res) resource;
            if (installed.containsValue(r)) findings.add(r + " closed while installed");
            r.closeAttempts++;
            if (failCloses) {
                r.closeFailures++;
                throw new IllegalStateException("simulated close failure of " + r);
            }
            r.closes++;
        }

        /** Every resource has one accounted attempt; failures are explicit, not successful closes. */
        String accountingErrors() {
            StringBuilder b = new StringBuilder();
            for (Res r : opened) {
                if (r.closeAttempts != 1 || r.closes + r.closeFailures != 1) {
                    b.append(r).append(" attempts=").append(r.closeAttempts).append(" successes=").append(r.closes)
                            .append(" failures=").append(r.closeFailures).append("; ");
                }
            }
            if (!installed.isEmpty()) b.append("still installed: ").append(installed.values());
            if (!findings.isEmpty()) b.append("findings: ").append(findings);
            return b.toString();
        }

        java.util.Set<Long> failedCloseResources() {
            return opened.stream().filter(r -> r.closeFailures != 0).map(r -> (long) r.serial)
                    .collect(java.util.stream.Collectors.toSet());
        }

        /** Every resource opened has been closed exactly once and nothing is installed. */
        String leaks() {
            StringBuilder b = new StringBuilder();
            for (Res r : opened) {
                if (r.closes != 1) b.append(r).append(" closed ").append(r.closes).append(" times; ");
            }
            if (!installed.isEmpty()) b.append("still installed: ").append(installed.values());
            if (!findings.isEmpty()) b.append("findings: ").append(findings);
            return b.toString();
        }
    }

    private CaptureRig() {
    }
}
