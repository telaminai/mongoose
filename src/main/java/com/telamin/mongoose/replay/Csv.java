package com.telamin.mongoose.replay;

import java.util.ArrayList;
import java.util.List;

/** The minimal CSV the sample journal and store write: a field is quoted when it holds a comma or a quote. */
final class Csv {
    private Csv() { }

    private static final java.util.logging.Logger log = java.util.logging.Logger.getLogger(Csv.class.getName());

    /** A file's complete lines, and its torn last line (null when it ends cleanly). */
    record Lines(List<String> lines, String torn) { }

    /**
     * The file's lines. A last line with no line break is a write torn by a crash: it is set aside, with a warning, so
     * the rest is still read. The file must not then be appended to: the next line would join the torn one (review of
     * 90f0d9b, finding 7). A malformed line anywhere else is still an error.
     */
    static Lines read(java.nio.file.Path file) throws java.io.IOException {
        String text = java.nio.file.Files.readString(file, java.nio.charset.StandardCharsets.UTF_8);
        List<String> lines = new ArrayList<>(text.lines().toList());
        String torn = null;
        if (!text.isEmpty() && !text.endsWith("\n") && !lines.isEmpty()) {
            torn = lines.remove(lines.size() - 1);
            log.warning(file + ": set aside a torn last line (no line break, a write the process did not finish): " + torn);
        }
        return new Lines(lines, torn);
    }

    /** The refusal for appending to a file with a torn last line. */
    static IllegalStateException tornRefusal(java.nio.file.Path file, String torn) {
        return new IllegalStateException(file + " ends in a torn line, a write the process did not finish (" + torn
                + "); it is read, never appended to: record into an empty file");
    }

    static String field(String s) {
        // the files are read line by line, so a field may not span lines (a base64 payload never does)
        if (s.indexOf('\n') >= 0 || s.indexOf('\r') >= 0) {
            throw new IllegalArgumentException("a name holding a line break cannot be written to a CSV line: " + s);
        }
        if (s.indexOf(',') < 0 && s.indexOf('"') < 0 && s.indexOf('\n') < 0 && s.indexOf('\r') < 0) return s;
        return '"' + s.replace("\"", "\"\"") + '"';
    }

    static List<String> split(String line) {
        List<String> out = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        boolean quoted = false;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (quoted) {
                if (c == '"' && i + 1 < line.length() && line.charAt(i + 1) == '"') {
                    cur.append('"');
                    i++;
                } else if (c == '"') {
                    quoted = false;
                } else {
                    cur.append(c);
                }
            } else if (c == '"') {
                quoted = true;
            } else if (c == ',') {
                out.add(cur.toString());
                cur.setLength(0);
            } else {
                cur.append(c);
            }
        }
        if (quoted) throw new IllegalArgumentException("an unterminated quoted field: " + line);
        out.add(cur.toString());
        return out;
    }
}
