package net.unit8.notation199x;

import org.jspecify.annotations.Nullable;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

/**
 * The vectors in {@code suite/} every implementation runs, and the fixtures of an image format in
 * {@code image/} every implementation that reads it runs, read as {@code suite/README.md} states
 * them: a line of fields separated by {@code ;}, a text as its scalar values in hex, and before the
 * first line of vectors the sources every field is held to.
 *
 * <p>A line is read whole or not at all. Every field is read through an accessor that takes only
 * what the format writes there, and a field no check reads is refused: one a line leaves unasserted
 * is read as {@link Line#empty}. So nothing written in a file goes unchecked, and a field that says
 * something no runner asserts cannot be written.
 */
public final class Suite {

    private Suite() {}

    /** One line of vectors, and which of its fields have been read. */
    public static final class Line {

        private final String where;
        private final List<String> fields;
        private final boolean[] read;

        private Line(String where, List<String> fields) {
            this.where = where;
            this.fields = fields;
            this.read = new boolean[fields.size()];
        }

        /** The file and the line number, for a message. */
        public String where() {
            return where;
        }

        private String take(int index) {
            read[index] = true;
            return fields.get(index);
        }

        private IllegalStateException wrong(int index, String what) {
            return new IllegalStateException(where + ": field " + (index + 1) + " is \"" + fields.get(index)
                    + "\", not " + what);
        }

        /** The field at {@code index}, which a line leaves empty where it asserts nothing there. */
        public void empty(int index) {
            if (!take(index).isEmpty()) {
                throw wrong(index, "empty");
            }
        }

        /** The field at {@code index} as the text it writes. */
        public String text(int index) {
            return Suite.text(take(index), where);
        }

        /** The field at {@code index} as it is written, for a file that says a field is written so,
         *  as {@code image/p1.txt} writes an image. */
        public String asWritten(int index) {
            return take(index);
        }

        /** The field at {@code index} as the one scalar value it writes. */
        public int scalar(int index) {
            String text = text(index);
            if (text.codePointCount(0, text.length()) != 1) {
                throw wrong(index, "one scalar value");
            }
            return text.codePointAt(0);
        }

        /** The field at {@code index} as an unsigned decimal. */
        public long number(int index) {
            String field = take(index);
            if (!field.matches("0|[1-9][0-9]{0,17}")) {
                throw wrong(index, "an unsigned decimal");
            }
            return Long.parseLong(field);
        }

        /** The field at {@code index} as an unsigned decimal, or null where it is empty. */
        public @Nullable Long numberOrNothing(int index) {
            if (fields.get(index).isEmpty()) {
                empty(index);
                return null;
            }
            return number(index);
        }

        /** The field at {@code index} as one of {@code names}, or null where it is empty. */
        public @Nullable String oneOfOrNothing(int index, String... names) {
            if (fields.get(index).isEmpty()) {
                empty(index);
                return null;
            }
            return oneOf(index, names);
        }

        /** The field at {@code index} as {@code true} or {@code false}. */
        public boolean yesOrNo(int index) {
            return switch (take(index)) {
                case "true" -> true;
                case "false" -> false;
                default -> throw wrong(index, "true or false");
            };
        }

        /** The field at {@code index} as one of {@code names}. */
        public String oneOf(int index, String... names) {
            String field = take(index);
            for (String name : names) {
                if (name.equals(field)) {
                    return field;
                }
            }
            throw wrong(index, "one of " + List.of(names));
        }

        private void readWhole() {
            for (int i = 0; i < read.length; i++) {
                if (!read[i]) {
                    throw new IllegalStateException(where + ": field " + (i + 1) + " is not read");
                }
            }
        }
    }

    /**
     * What is wrong with an implementation, line by line, over {@code name} in {@code suite/}.
     *
     * @param name   the file, {@code case.txt}
     * @param fields how many fields a line of it has
     * @param check  what is wrong with the implementation on a line, or null where nothing is; it
     *               reads every field of the line
     * @return the file and line of each wrong answer, with what was wrong
     */
    public static List<String> wrong(String name, int fields, Function<Line, @Nullable String> check) {
        return wrong(Path.of("..", "suite", name), fields, check);
    }

    /**
     * {@link #wrong(String, int, Function)} over {@code file}, a path from {@code java/}, for the
     * fixtures of an image format, which are read as the suite is and are not in it.
     *
     * @param file   the file, {@code ../image/p1.txt}
     * @param fields how many fields a line of it has
     * @param check  what is wrong with the implementation on a line, or null where nothing is
     * @return the file and line of each wrong answer, with what was wrong
     */
    public static List<String> wrong(Path file, int fields, Function<Line, @Nullable String> check) {
        List<String> wrong = new ArrayList<>();
        for (Line line : read(file, fields)) {
            String said = check.apply(line);
            line.readWhole();
            if (said != null) {
                wrong.add(line.where() + ": " + said);
            }
        }
        return wrong;
    }

    private static List<Line> read(Path file, int fields) {
        String name = file.getFileName().toString();
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
        boolean sourced = false;
        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i);
            if (line.equals("# Source:") && i + 1 < lines.size() && lines.get(i + 1).startsWith("#   ")) {
                sourced = true;
            }
            if (line.isEmpty() || line.startsWith("#")) {
                continue;
            }
            if (!sourced) {
                throw new IllegalStateException(name + " names no source before its first vector");
            }
            String where = name + ":" + (i + 1);
            List<String> split = List.of(line.split(";", -1)).stream().map(String::strip).toList();
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
    private static String text(String field, String where) {
        if (field.isEmpty()) {
            return "";
        }
        StringBuilder text = new StringBuilder();
        for (String each : field.split(" +")) {
            if (!each.matches("[0-9A-F]{4,6}")) {
                throw new IllegalStateException(where + ": \"" + each + "\" is not a scalar value in hex");
            }
            int scalar = Integer.parseInt(each, 16);
            if (scalar > 0x10FFFF || scalar >= 0xD800 && scalar <= 0xDFFF) {
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
