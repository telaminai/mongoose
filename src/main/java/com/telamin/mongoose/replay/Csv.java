package com.telamin.mongoose.replay;

import java.util.ArrayList;
import java.util.List;

/** The minimal CSV the sample journal and store write: a field is quoted when it holds a comma or a quote. */
final class Csv {
    private Csv() { }

    private static final java.util.logging.Logger log = java.util.logging.Logger.getLogger(Csv.class.getName());

    /**
     * The file's lines. A last line with no line break is a write torn by a crash: it is dropped, with a warning, so
     * the rest is still read. A malformed line anywhere else is still an error.
     */
    static List<String> lines(java.nio.file.Path file) throws java.io.IOException {
        String text = java.nio.file.Files.readString(file, java.nio.charset.StandardCharsets.UTF_8);
        List<String> lines = new ArrayList<>(text.lines().toList());
        if (!text.isEmpty() && !text.endsWith("\n") && !lines.isEmpty()) {
            String torn = lines.remove(lines.size() - 1);
            log.warning(file + ": dropped a torn last line (no line break, a write the process did not finish): " + torn);
        }
        return lines;
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
