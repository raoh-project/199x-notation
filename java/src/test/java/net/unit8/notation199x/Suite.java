package net.unit8.notation199x;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * The vectors in {@code suite/} every implementation runs, read as {@code suite/README.md} states
 * them: a line of fields separated by {@code ;}, a text as its scalar values in hex.
 */
public final class Suite {

    private Suite() {}

    /**
     * One line of vectors.
     *
     * @param where  the file and the line number, for a message
     * @param fields the fields, trimmed
     */
    public record Line(String where, List<String> fields) {

        /** The field at {@code index}. */
        public String field(int index) {
            return fields.get(index);
        }

        /** The field at {@code index} as the text it writes. */
        public String text(int index) {
            return Suite.text(fields.get(index), where);
        }
    }

    /**
     * The lines of {@code name} in {@code suite/}, each held to {@code fields} fields.
     *
     * @param name   the file, {@code case.txt}
     * @param fields how many fields a line of it has
     * @return its lines of vectors, comments and blank lines left out
     */
    public static List<Line> read(String name, int fields) {
        Path file = Path.of("..", "suite", name);
        if (!Files.exists(file)) {
            throw new IllegalStateException(file.toAbsolutePath() + " is missing: the tests run in java/");
        }
        List<String> lines;
        try {
            lines = Files.readAllLines(file, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        List<Line> read = new ArrayList<>();
        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i);
            if (line.isEmpty() || line.startsWith("#")) {
                continue;
            }
            String where = name + ":" + (i + 1);
            List<String> split = List.of(line.split(";", -1)).stream().map(field -> field.strip()).toList();
            if (split.size() != fields) {
                throw new IllegalStateException(where + " has " + split.size() + " fields, not " + fields);
            }
            read.add(new Line(where, split));
        }
        if (read.isEmpty()) {
            throw new IllegalStateException(name + " holds no vectors");
        }
        return read;
    }

    /** The text {@code field} writes: scalar values in hex, separated by spaces, and none where it is
     *  empty. A surrogate or a number past U+10FFFF is no scalar value, and no text the suite holds. */
    static String text(String field, String where) {
        if (field.isEmpty()) {
            return "";
        }
        StringBuilder text = new StringBuilder();
        for (String each : field.split(" +")) {
            if (!each.matches("[0-9A-F]{4,6}")) {
                throw new IllegalStateException(where + ": \"" + each + "\" is not a scalar value in hex");
            }
            int scalar = Integer.parseInt(each, 16);
            if (scalar > Character.MAX_CODE_POINT || scalar >= 0xD800 && scalar <= 0xDFFF) {
                throw new IllegalStateException(where + ": U+" + each + " is no scalar value");
            }
            text.appendCodePoint(scalar);
        }
        return text.toString();
    }

    /**
     * {@code text} as a message shows it: printable ASCII as itself, and every other scalar value as
     * {@code <U+XXXX>}.
     *
     * @param text a text read from the suite
     * @return it, in quotes, for a message
     */
    public static String shown(String text) {
        StringBuilder shown = new StringBuilder("\"");
        text.codePoints().forEach(cp -> {
            if (cp >= 0x20 && cp < 0x7F) {
                shown.appendCodePoint(cp);
            } else {
                shown.append("<U+").append("%04X".formatted(cp)).append('>');
            }
        });
        return shown.append('"').toString();
    }
}
