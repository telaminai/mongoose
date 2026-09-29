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
 * {@code codec}; each entry's clock readings are {@code ;}-separated. Appended and flushed per entry; read back whole
 * when opened again. A sample, like {@link CsvEventJournal}.
 */
@Experimental
public final class CsvReplayStore implements ReplayStore, AutoCloseable {

    static final String HEADER = "processor,kind,source,seq,payload,reads";

    private final Path file;
    private final EventCodec codec;
    private final Map<String, List<ReplayEntry>> entries = new ConcurrentHashMap<>();
    private final BufferedWriter out;
    /** A torn last line found on opening, or null: the file is then read, and never appended to. */
    private String torn;

    public CsvReplayStore(Path file, EventCodec codec) {
        this.file = file;
        this.codec = codec;
        try {
            if (Files.exists(file)) {
                Csv.Lines read = Csv.read(file);
                torn = read.torn();
                List<String> lines = read.lines();
                for (int i = 0; i < lines.size(); i++) {
                    if (i == 0) {
                        if (!lines.get(0).equals(HEADER)) throw new IllegalArgumentException(file + " is not a replay store: " + lines.get(0));
                        continue;
                    }
                    if (lines.get(i).isEmpty()) continue;
                    List<String> f = Csv.split(lines.get(i));
                    if (f.size() != 6) throw new IllegalArgumentException(file + " line " + (i + 1) + " has " + f.size() + " fields, not 6");
                    add(f.get(0), parse(f));
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
        return torn != null || entries.values().stream().anyMatch(l -> !l.isEmpty());
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
            case ReplayEntry.Indexed i -> "INDEXED," + Csv.field(i.source()) + "," + i.seq() + ",," + reads(i.reads());
            case ReplayEntry.Inline in -> "INLINE," + Csv.field(in.source()) + "," + (in.seq() < 0 ? "" : in.seq()) + ","
                    + encode(in.event()) + "," + reads(in.reads());
            case ReplayEntry.TimerFired t -> "TIMER,," + t.seq() + ",," + reads(t.reads());
            case ReplayEntry.AdminInvoked a -> "ADMIN," + Csv.field(a.command()) + ",," + encode(new ArrayList<>(a.args())) + "," + reads(a.reads());
            case ReplayEntry.Failed f -> "FAILED," + Csv.field(f.source()) + ",," + encode(f.description()) + "," + f.instant();
        };
    }

    @SuppressWarnings("unchecked")
    private ReplayEntry parse(List<String> f) {
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
