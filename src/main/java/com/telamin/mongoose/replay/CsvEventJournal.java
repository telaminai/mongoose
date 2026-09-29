package com.telamin.mongoose.replay;

import com.telamin.fluxtion.runtime.annotations.feature.Experimental;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * A durable journal in a CSV file, to start (spec-replay-recording §5): one line per journalled item,
 * {@code source,seq,base64(encoded item)}, under a {@code source,seq,item} header. Appended and flushed as each item is
 * published, so it survives the process; opened again, it is read back whole. The encoded bytes are the feed codec's,
 * so the file is as readable as the codec's output: a text codec gives readable CSV after one base64 decode.
 *
 * <p>A sample, not a production store: the index is in memory, flushing is per line with no fsync, and one file holds
 * every source. A production journal (Chronicle) indexes by offset, rolls, and retains by policy.
 */
@Experimental
public final class CsvEventJournal implements EventJournal, AutoCloseable {

    static final String HEADER = "source,seq,item";

    private final Path file;
    private final Map<String, Map<Long, byte[]>> index = new ConcurrentHashMap<>();
    private final BufferedWriter out;
    /** A torn last line found on opening, or null: the file is then read, and never appended to. */
    private String torn;

    /** Open {@code file}, reading back what it already holds, and append to it. */
    public CsvEventJournal(Path file) {
        this.file = file;
        try {
            if (Files.exists(file)) {
                Csv.Lines read = Csv.read(file);
                torn = read.torn();
                List<String> lines = read.lines();
                for (int i = 0; i < lines.size(); i++) {
                    String line = lines.get(i);
                    if (i == 0) {
                        if (!line.equals(HEADER)) throw new IllegalArgumentException(file + " is not an event journal: " + line);
                        continue;
                    }
                    if (line.isEmpty()) continue;
                    List<String> f = Csv.split(line);
                    if (f.size() != 3) throw new IllegalArgumentException(file + " line " + (i + 1) + " is not source,seq,item");
                    put(f.get(0), Long.parseLong(f.get(1)), Base64.getDecoder().decode(f.get(2)));
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
            throw new UncheckedIOException("cannot open journal " + file, e);
        }
    }

    @Override
    public synchronized void append(String source, long seq, byte[] encoded) {
        if (torn != null) throw Csv.tornRefusal(file, torn);
        try {
            out.write(Csv.field(source) + "," + seq + "," + Base64.getEncoder().encodeToString(encoded));
            out.newLine();
            out.flush();
        } catch (IOException e) {
            throw new UncheckedIOException("cannot append to journal " + file, e);
        }
        put(source, seq, encoded);                      // after the write, so memory never holds what the file does not
    }

    @Override
    public boolean holdsRecording() {
        return torn != null || !index.isEmpty();
    }

    @Override
    public byte[] get(String source, long seq) {
        Map<Long, byte[]> bySeq = index.get(source);
        return bySeq == null ? null : bySeq.get(seq);
    }

    public int size(String source) {
        Map<Long, byte[]> bySeq = index.get(source);
        return bySeq == null ? 0 : bySeq.size();
    }

    private void put(String source, long seq, byte[] encoded) {
        index.computeIfAbsent(source, s -> new ConcurrentHashMap<>()).put(seq, encoded);
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
