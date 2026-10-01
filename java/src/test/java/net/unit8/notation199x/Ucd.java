package net.unit8.notation199x;

import java.nio.file.Files;
import java.nio.file.Path;

/** The Unicode Character Database files of the pinned version, which the repository holds beside
 *  every language's implementation. */
public final class Ucd {

    public static final String VERSION = "18.0.0";

    private Ucd() {}

    /** {@code name} in {@code ucd/<version>/}, found from the {@code java/} directory a build runs in. */
    public static Path file(String name) {
        Path file = Path.of("..", "ucd", VERSION, name);
        if (!Files.exists(file)) {
            throw new IllegalStateException(file.toAbsolutePath() + " is missing: the tests run in java/");
        }
        return file;
    }
}
