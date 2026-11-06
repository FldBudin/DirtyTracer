package io.fldbudin.dirtytracer;

import android.os.Process;
import android.util.Log;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Isolated readproc crash_dump TracerPid probe.
 *
 * Runs inside a plain isolated process, which inherits zygote's readproc gid 3009.
 * crash_dump64 then reads {@code /proc/<pid>/status} for a target and logs an
 * "already traced by" FATAL when the target has a tracer; logd hands the line
 * back to this same isolated uid.
 */
final class Probe {
    private static final String CRASH_DUMP = "/apex/com.android.runtime/bin/crash_dump64";
    private static final String LOGCAT = "/system/bin/logcat";
    private static final File DEV_NULL = new File("/dev/null");

    private static final int INIT_ATTEMPTS = 3;
    private static final int ZYGOTE_ATTEMPTS = 1;
    // A cold crash_dump64 takes over a second to reach PTRACE_SEIZE and much
    // longer while the system is busy after an install; killing it early drops
    // the only log line we can observe.
    private static final long CRASH_DUMP_TIMEOUT_MS = 6000L;
    private static final long PROBE_GAP_MS = 600L;
    private static final long LOG_SETTLE_MS = 250L;
    private static final long RE_READ_DELAY_MS = 700L;
    private static final int RETRY_ROUNDS = 1;
    private static final long RETRY_DELAY_MS = 800L;
    private static final double MAX_WINDOW_SECONDS = 8.0;

    private static final String CRASH_DUMP_BODY = "failed to seize pseudothread: ";

    private static String lastReadInfo = "";

    private static final Pattern TRACED = Pattern.compile(
            "^failed to seize pseudothread: failed to attach to thread (\\d+), " +
                    "already traced by (\\d+) \\(([^)]*)\\)$");
    private static final Pattern STOCK = Pattern.compile(
            "^failed to seize pseudothread: failed to attach to thread (\\d+): " +
                    "Operation not permitted$");

    private Probe() {
    }

    static final class TargetResult {
        final String name;
        final int pid;
        String state = "UNDETERMINED";
        int tracerPid = -1;
        String tracerName = "";
        String evidence = "";
        String note = "";
        int attempts;
        int matches;

        TargetResult(String name, int pid) {
            this.name = name;
            this.pid = pid;
        }
    }

    private static final class Attempt {
        int targetPid;
        double t0;
        long durationMs;
        int exit = -1;
        boolean timedOut;
        boolean traced;
        boolean stock;
        int tracerPid = -1;
        String tracerName = "";
        String evidence = "";
    }

    static String run(int zygotePid) {
        List<TargetResult> targets = new ArrayList<>();
        TargetResult init = new TargetResult("init", 1);
        init.note = "This probe is positive-only; stock evidence leaves the verdict INDETERMINATE.";
        targets.add(init);

        TargetResult zygote = null;
        if (zygotePid > 1) {
            zygote = new TargetResult("zygote", zygotePid);
            zygote.note = "The tracer releases zygote after injection; a tracer is only " +
                    "visible inside that window.";
            targets.add(zygote);
        }

        List<Attempt> attempts = new ArrayList<>();
        List<String> lines;
        for (int round = 0; ; round++) {
            attempts.clear();
            for (int i = 0; i < INIT_ATTEMPTS; i++) {
                attempts.add(launch(init.pid));
                sleep(PROBE_GAP_MS);
            }
            if (zygote != null) {
                for (int i = 0; i < ZYGOTE_ATTEMPTS; i++) {
                    attempts.add(launch(zygote.pid));
                    sleep(PROBE_GAP_MS);
                }
            }

            sleep(LOG_SETTLE_MS);
            lines = readLog();
            if (!containsSeizeLine(lines)) {
                // The last crash_dump64 may still be flushing into logd.
                sleep(RE_READ_DELAY_MS);
                lines = readLog();
            }
            for (int i = 0; i < attempts.size(); i++) {
                Attempt attempt = attempts.get(i);
                // An attempt owns every line from its start until the next attempt
                // starts, so a slow crash_dump64 is never cut off by a fixed window.
                double end = i + 1 < attempts.size()
                        ? attempts.get(i + 1).t0
                        : attempt.t0 + MAX_WINDOW_SECONDS;
                classify(attempt, lines, attempt.targetPid, attempt.t0, end);
            }
            apply(targets, attempts);
            if (round >= RETRY_ROUNDS || !"UNDETERMINED".equals(init.state)) break;
            // A round that saw no log line at all can be a logging artifact rather
            // than a clean result; one more round gives fresh entries to read.
            Log.i("DirtyTracer", "round " + round + ": no evidence, retrying");
            sleep(RETRY_DELAY_MS);
        }

        String detail = report(targets, attempts, lines);
        Log.i("DirtyTracer", detail);
        return summary(targets);
    }

    private static String summary(List<TargetResult> targets) {
        boolean detected = false;
        for (TargetResult target : targets) {
            if ("init".equals(target.name) && "TRACED".equals(target.state)) detected = true;
        }

        StringBuilder sb = new StringBuilder();
        for (TargetResult target : targets) {
            sb.append(target.name).append(" (pid ").append(target.pid).append("): ");
            if ("TRACED".equals(target.state)) {
                sb.append("TRACED by ").append(target.tracerPid)
                        .append(" (").append(target.tracerName).append(')');
            } else if ("NOT_TRACED".equals(target.state)) {
                sb.append("NOT TRACED");
            } else {
                sb.append("INDETERMINATE");
            }
            sb.append('\n');
        }
        sb.append("verdict: ").append(detected ? "DETECTED" : "INDETERMINATE");
        return sb.toString();
    }

    private static void apply(List<TargetResult> targets, List<Attempt> attempts) {
        for (TargetResult target : targets) {
            int count = 0;
            int hits = 0;
            Attempt traced = null;
            Attempt stock = null;
            Attempt first = null;

            for (Attempt attempt : attempts) {
                if (attempt.targetPid != target.pid) continue;
                count++;
                if (first == null) first = attempt;
                if (attempt.traced || attempt.stock) hits++;
                if (attempt.traced && traced == null) traced = attempt;
                if (attempt.stock && stock == null) stock = attempt;
            }

            target.attempts = count;
            target.matches = hits;
            if (traced != null) {
                target.state = "TRACED";
                target.tracerPid = traced.tracerPid;
                target.tracerName = traced.tracerName;
                target.evidence = traced.evidence;
            } else if (stock != null) {
                target.state = "NOT_TRACED";
                target.evidence = stock.evidence;
            } else {
                target.state = "UNDETERMINED";
                target.evidence = first != null ? first.evidence : "";
            }
        }
    }

    private static Attempt launch(int targetPid) {
        Attempt attempt = new Attempt();
        attempt.targetPid = targetPid;
        long startMs = System.currentTimeMillis();
        attempt.t0 = startMs / 1000.0;

        java.lang.Process crash = null;
        try {
            crash = new ProcessBuilder(
                    CRASH_DUMP,
                    Integer.toString(targetPid),
                    Integer.toString(targetPid),
                    "0")
                    .redirectErrorStream(true)
                    .redirectOutput(ProcessBuilder.Redirect.to(DEV_NULL))
                    .start();
            if (!crash.waitFor(CRASH_DUMP_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
                attempt.timedOut = true;
                crash.destroyForcibly();
            } else {
                attempt.exit = crash.exitValue();
            }
        } catch (Throwable t) {
            if (crash != null) crash.destroyForcibly();
            attempt.evidence = "exec failed: " + t;
        }
        attempt.durationMs = System.currentTimeMillis() - startMs;
        return attempt;
    }

    private static List<String> readLog() {
        List<String> lines = new ArrayList<>();
        java.lang.Process logcat = null;
        long startMs = System.currentTimeMillis();
        String info = "";
        try {
            logcat = new ProcessBuilder(LOGCAT, "-d", "-v", "epoch", "-s", "crash_dump64")
                    .redirectErrorStream(true)
                    .start();
            try (BufferedReader reader =
                         new BufferedReader(new InputStreamReader(logcat.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    lines.add(line);
                    if (lines.size() >= 4000) break;
                }
            }
            if (logcat.waitFor(1000, TimeUnit.MILLISECONDS)) {
                info = " exit=" + logcat.exitValue();
            } else {
                info = " exit=timeout";
                logcat.destroyForcibly();
            }
        } catch (Throwable t) {
            info = " err=" + t;
            if (logcat != null) logcat.destroyForcibly();
        }
        lastReadInfo = (System.currentTimeMillis() - startMs) + "ms lines="
                + lines.size() + info;
        return lines;
    }

    private static boolean containsSeizeLine(List<String> lines) {
        for (String line : lines) {
            if (line.contains(CRASH_DUMP_BODY)) return true;
        }
        return false;
    }

    private static void classify(Attempt attempt, List<String> lines, int targetPid,
                                 double t0, double end) {
        int matched = 0;
        String firstInWindow = "";
        Attempt found = null;

        for (String line : lines) {
            double epoch = epochOf(line);
            if (Double.isNaN(epoch) || epoch < t0 || epoch >= end) continue;
            String message = messageOf(line);
            if (message == null) continue;
            if (firstInWindow.isEmpty()) firstInWindow = line.trim();

            Matcher traced = TRACED.matcher(message);
            if (traced.matches() && Integer.parseInt(traced.group(1)) == targetPid) {
                matched++;
                int tracer = Integer.parseInt(traced.group(2));
                if (tracer > 0) {
                    found = new Attempt();
                    found.traced = true;
                    found.tracerPid = tracer;
                    found.tracerName = traced.group(3);
                    found.evidence = line.trim();
                }
                continue;
            }

            Matcher stock = STOCK.matcher(message);
            if (stock.matches() && Integer.parseInt(stock.group(1)) == targetPid) {
                matched++;
                if (found == null || !found.traced) {
                    found = new Attempt();
                    found.stock = true;
                    found.evidence = line.trim();
                }
            }
        }

        if (matched > 0 && found != null) {
            attempt.traced = found.traced;
            attempt.stock = found.stock;
            attempt.tracerPid = found.tracerPid;
            attempt.tracerName = found.tracerName;
            attempt.evidence = found.evidence;
        } else {
            attempt.evidence = found != null ? found.evidence : firstInWindow;
        }
    }

    private static double epochOf(String line) {
        // -v epoch right-aligns the timestamp in a 19-character field, so the
        // line starts with spaces; trim before reading the first token.
        String trimmed = line.trim();
        int space = trimmed.indexOf(' ');
        if (space <= 0) return Double.NaN;
        try {
            return Double.parseDouble(trimmed.substring(0, space));
        } catch (NumberFormatException e) {
            return Double.NaN;
        }
    }

    private static String messageOf(String line) {
        // LOG(FATAL) prefixes the message with "file:line] "; anchor on the
        // canonical body so both prefixed and bare lines match.
        String trimmed = line.trim();
        int start = trimmed.indexOf(CRASH_DUMP_BODY);
        return start < 0 ? null : trimmed.substring(start);
    }

    private static String attemptTrace(List<Attempt> attempts, int targetPid) {
        StringBuilder sb = new StringBuilder();
        int index = 0;
        for (Attempt attempt : attempts) {
            if (attempt.targetPid != targetPid) continue;
            index++;
            if (sb.length() > 0) sb.append(" | ");
            sb.append('#').append(index)
                    .append(" t=").append(String.format(java.util.Locale.US, "%.2f", attempt.t0))
                    .append(' ').append(attempt.durationMs).append("ms ")
                    .append(attempt.timedOut ? "timeout" : "exit" + attempt.exit)
                    .append(' ');
            if (attempt.traced) sb.append("TRACED");
            else if (attempt.stock) sb.append("stock");
            else sb.append("miss");
        }
        return sb.toString();
    }

    private static String report(List<TargetResult> targets, List<Attempt> attempts,
                                 List<String> lines) {
        boolean detected = false;
        for (TargetResult target : targets) {
            if ("init".equals(target.name) && "TRACED".equals(target.state)) detected = true;
        }

        StringBuilder sb = new StringBuilder();
        sb.append("uid=").append(Process.myUid())
                .append(" isolated=").append(Process.isIsolated())
                .append(" logLines=").append(lines.size())
                .append(" read=").append(lastReadInfo).append('\n');
        sb.append("targets:");
        for (TargetResult target : targets) {
            sb.append(' ').append(target.name).append('=').append(target.pid);
        }
        sb.append('\n');
        sb.append("verdict: ").append(detected ? "DETECTED" : "INDETERMINATE").append('\n');

        for (TargetResult target : targets) {
            sb.append('\n').append("[target ").append(target.name)
                    .append("] pid=").append(target.pid).append('\n');
            sb.append("  state: ").append(target.state).append('\n');
            if (target.tracerPid > 0) {
                sb.append("  tracer: ").append(target.tracerPid)
                        .append(" (").append(target.tracerName).append(")\n");
            }
            sb.append("  attempts: ").append(target.attempts)
                    .append(", window matches: ").append(target.matches).append('\n');
            sb.append("  by attempt: ").append(attemptTrace(attempts, target.pid)).append('\n');
            if (!target.note.isEmpty()) sb.append("  note: ").append(target.note).append('\n');
            if (!target.evidence.isEmpty()) {
                sb.append("  evidence: ").append(target.evidence).append('\n');
            }
        }
        return sb.toString().trim();
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
