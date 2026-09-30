package com.telamin.mongoose.replay;

import com.telamin.fluxtion.runtime.annotations.feature.Experimental;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.Collectors;

/**
 * Each processor's entries in a CSV file, to start: one line per entry, {@code processor,kind,...}, under a header. An
 * index names its journal entry and needs no payload; an inline input or an admin command's arguments are encoded with
 * {@code codec}; each entry names its route and its instant, and its clock readings are {@code ;}-separated (none
 * for a cycle that read no clock). A file of the earlier six-field format, with neither, is still read. Appended and flushed per entry; read back whole
 * when opened again. A sample, like {@link CsvEventJournal}.
 */
@Experimental
public final class CsvReplayStore implements ReplayStore, AutoCloseable {

    static final String HEADER = "processor,kind,source,route,seq,instant,payload,reads";
    /** The format before entries named their route and instant (review of 90f0d9b, findings 4 and 6): read, not written. */
    static final String HEADER_6 = "processor,kind,source,seq,payload,reads";

    private final Path file;
    private final EventCodec codec;
    private final Map<String, List<ReplayEntry>> entries = new ConcurrentHashMap<>();
    private final BufferedWriter out;
    /** A torn last line found on opening, or null: the file is then read, and never appended to. */
    private String torn;
    /**
     * The file is in the earlier six-field format (re-review N6): it is read, and never appended to, because this writer
     * writes eight fields and the next open would read them under the six-field header and refuse the file.
     */
    private boolean earlierFormat;

    public CsvReplayStore(Path file, EventCodec codec) {
        this.file = file;
        this.codec = codec;
        try {
            if (Files.exists(file)) {
                Csv.Lines read = Csv.read(file);
                torn = read.torn();
                List<String> lines = read.lines();
                int fields = 0;
                for (int i = 0; i < lines.size(); i++) {
                    if (i == 0) {
                        fields = lines.get(0).equals(HEADER) ? 8 : lines.get(0).equals(HEADER_6) ? 6 : 0;
                        if (fields == 0) throw new IllegalArgumentException(file + " is not a replay store: " + lines.get(0));
                        earlierFormat = fields == 6;
                        continue;
                    }
                    if (lines.get(i).isEmpty()) continue;
                    List<String> f = Csv.split(lines.get(i));
                    if (f.size() != fields) throw new IllegalArgumentException(file + " line " + (i + 1) + " has " + f.size() + " fields, not " + fields);
                    add(f.get(0), fields == 8 ? parse(f) : parse6(f));
                }
            }
            boolean fresh = !Files.exists(file) || Files.size(file) == 0;
            out = Files.newBufferedWriter(file, StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            if (fresh) {
                out.write(HEADER);
                out.newLine();
                out.flush();
            }
        } catch (IOException e) {
            throw new UncheckedIOException("cannot open replay store " + file, e);
        }
    }

    @Override
    public synchronized void append(String processor, ReplayEntry entry) {
        if (torn != null) throw Csv.tornRefusal(file, torn);
        if (earlierFormat) {
            throw new IllegalStateException(file + " is a replay store in the earlier six-field format; it is read, never "
                    + "appended to (an eight-field entry under its header would make it unreadable): record into an empty file");
        }
        String line = Csv.field(processor) + "," + format(entry);   // encoding first: an item that cannot be encoded adds nothing
        try {
            out.write(line);
            out.newLine();
            out.flush();
        } catch (IOException e) {
            throw new UncheckedIOException("cannot append to replay store " + file, e);
        }
        add(processor, entry);                          // after the write, so memory never holds what the file does not
    }

    @Override
    public boolean holdsRecording() {
        return torn != null || earlierFormat || entries.values().stream().anyMatch(l -> !l.isEmpty());
    }

    @Override
    public List<ReplayEntry> entries(String processor) {
        return List.copyOf(entries.getOrDefault(processor, List.of()));
    }

    private void add(String processor, ReplayEntry entry) {
        entries.computeIfAbsent(processor, p -> new CopyOnWriteArrayList<>()).add(entry);
    }

    private String format(ReplayEntry e) {
        return switch (e) {
            case ReplayEntry.Indexed i -> "INDEXED," + Csv.field(i.source()) + "," + Csv.field(i.route()) + "," + i.seq() + ","
                    + i.instant() + ",," + reads(i.reads());
            case ReplayEntry.Inline in -> "INLINE," + Csv.field(in.source()) + "," + Csv.field(in.route()) + ","
                    + (in.seq() < 0 ? "" : in.seq()) + "," + in.instant() + "," + encode(in.event()) + "," + reads(in.reads());
            case ReplayEntry.TimerFired t -> "TIMER,,," + t.seq() + "," + t.instant() + ",," + reads(t.reads());
            case ReplayEntry.AdminInvoked a -> "ADMIN," + Csv.field(a.command()) + ",,," + a.instant() + ","
                    + encode(new ArrayList<>(a.args())) + "," + reads(a.reads());
            case ReplayEntry.Failed f -> "FAILED," + Csv.field(f.source()) + ",,," + f.instant() + "," + encode(f.description()) + ",";
        };
    }

    @SuppressWarnings("unchecked")
    private ReplayEntry parse(List<String> f) {
        String kind = f.get(1), source = f.get(2), route = f.get(3), seq = f.get(4), payload = f.get(6);
        long instant = Long.parseLong(f.get(5));
        List<Long> reads = reads(f.get(7));
        return switch (kind) {
            case "INDEXED" -> new ReplayEntry.Indexed(source, route, Long.parseLong(seq), instant, reads);
            case "INLINE" -> new ReplayEntry.Inline(source, route, decode(payload), seq.isEmpty() ? -1 : Long.parseLong(seq), instant, reads);
            case "TIMER" -> new ReplayEntry.TimerFired(Long.parseLong(seq), instant, reads);
            case "ADMIN" -> new ReplayEntry.AdminInvoked(source, List.copyOf((List<String>) decode(payload)), instant, reads);
            case "FAILED" -> new ReplayEntry.Failed(source, (String) decode(payload), instant);
            default -> throw new IllegalArgumentException(file + ": an unknown entry kind " + kind);
        };
    }

    /** The six-field format: no route, and the instant was the first reading (a cycle always recorded one). */
    @SuppressWarnings("unchecked")
    private ReplayEntry parse6(List<String> f) {
        String kind = f.get(1), source = f.get(2), seq = f.get(3), payload = f.get(4), reads = f.get(5);
        return switch (kind) {
            case "INDEXED" -> new ReplayEntry.Indexed(source, Long.parseLong(seq), reads(reads));
            case "INLINE" -> new ReplayEntry.Inline(source, decode(payload), seq.isEmpty() ? -1 : Long.parseLong(seq), reads(reads));
            case "TIMER" -> new ReplayEntry.TimerFired(Long.parseLong(seq), reads(reads));
            case "ADMIN" -> new ReplayEntry.AdminInvoked(source, List.copyOf((List<String>) decode(payload)), reads(reads));
            case "FAILED" -> new ReplayEntry.Failed(source, (String) decode(payload), Long.parseLong(reads));
            default -> throw new IllegalArgumentException(file + ": an unknown entry kind " + kind);
        };
    }

    private String encode(Object o) {
        return Base64.getEncoder().encodeToString(codec.encode(o));
    }

    private Object decode(String s) {
        return codec.decode(Base64.getDecoder().decode(s));
    }

    private static String reads(List<Long> reads) {
        return reads.stream().map(String::valueOf).collect(Collectors.joining(";"));
    }

    private static List<Long> reads(String s) {
        if (s.isEmpty()) return List.of();              // a cycle that read no clock
        return Arrays.stream(s.split(";")).map(Long::parseLong).toList();
    }

    @Override
    public synchronized void close() {
        try {
            out.close();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
