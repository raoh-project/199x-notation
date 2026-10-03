import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.SortedMap;

/**
 * Writes a {@link UcdModel} as the Java sources {@code java/} reads.
 *
 * <p>What is decided here is how Java holds the data: which class each table is in, the doc comment
 * it carries, and the compact string each is written as and decoded from once a class is loaded. A
 * literal {@code int[][]} large enough to hold a mapping does not fit the JVM's 64&nbsp;KB
 * per-method bytecode limit as an array initializer, and one string constant holds at most 65,535
 * bytes. Nothing here is about Unicode.
 */
final class JavaEmitter {

    private static final String SOURCES = "java/src/main/java/net/unit8/notation199x/";

    private JavaEmitter() {}

    /**
     * Every source the model is written as, by where it goes.
     *
     * @param model the model
     * @return the path of each source, from the repository root, and its text
     */
    static Map<Path, String> render(UcdModel model) {
        Map<Path, String> sources = new LinkedHashMap<>();
        sources.put(Path.of(SOURCES + "CaseTables.java"), caseTables(model));
        sources.put(Path.of(SOURCES + "NormalizationTables.java"), normalizationTables(model));
        sources.put(Path.of(SOURCES + "WhiteSpaceTables.java"), whiteSpaceTables(model));
        sources.put(Path.of(SOURCES + "pattern/PatternAlphabetTables.java"), patternAlphabetTables(model));
        return sources;
    }

    // CaseTables

    private static String caseTables(UcdModel model) {
        String version = model.version();
        UcdModel.Casing casing = model.casing();
        StringBuilder out = new StringBuilder();
        out.append("package net.unit8.notation199x;\n\n");
        out.append("/**\n");
        out.append(" * The default case conversion tables {@link CaseConversion} reads: Unicode ").append(version)
                .append(", untailored full mapping.\n");
        out.append(" *\n");
        out.append(" * <p>Generated from Unicode ").append(version).append("'s {@code UnicodeData.txt}, ")
                .append("{@code SpecialCasing.txt} and {@code DerivedCoreProperties.txt}")
                .append(" ({@code https://www.unicode.org/Public/").append(version).append("/ucd/})")
                .append(" by {@code gen/Generate.java}. DO NOT EDIT — regenerate on a Unicode\n");
        out.append(" * version bump with {@code java gen/Generate.java ucd/<version>}, which this file's\n");
        out.append(" * source checksums let a reviewer confirm ran against the version it claims.\n");
        out.append(" *\n");
        out.append(" * <p>SHA-256, of the three input files as downloaded:\n");
        out.append(" * <ul>\n");
        for (String file : List.of("UnicodeData.txt", "SpecialCasing.txt", "DerivedCoreProperties.txt")) {
            out.append(" * <li>").append(file).append(": {@code ").append(model.sha256().get(file)).append("}\n");
        }
        out.append(" * </ul>\n");
        out.append(" */\n");
        out.append("final class CaseTables {\n\n");
        out.append("    private CaseTables() {}\n\n");
        out.append(CASE_DECODERS);
        out.append(PAGED_DECODERS);

        for (String[] each : new String[][] {{"LOWER", "lowercase"}, {"UPPER", "uppercase"}}) {
            UcdModel.CodePointMapping mapping = each[0].equals("LOWER") ? casing.lower() : casing.upper();
            out.append("    /** Unicode ").append(version).append("'s untailored full ").append(each[1])
                    .append(" mapping (")
                    .append(mapping.entries().size())
                    .append(" code points with a non-identity mapping; every other code point maps to itself). */\n");
            out.append("    static final Mapping ").append(each[0]).append(" = decodeMapping(\"")
                    .append(mapping(mapping)).append("\");\n\n");
        }

        out.append("    /** Code points whose {@link #LOWER} mapping is the untailored default, overridden by this\n");
        out.append("     *  mapping's result when the code point sits at the end of a cased run (Unicode's\n");
        out.append("     *  {@code Final_Sigma} condition), as Unicode ").append(version).append(" states it (")
                .append(codePoints(casing.finalSigma().entries().size())).append("). A mapping here may be\n");
        out.append("     *  of any length; it is the same {@link Mapping} shape {@link #LOWER}/{@link #UPPER} use,\n");
        out.append("     *  read the same way. */\n");
        out.append("    static final Mapping FINAL_SIGMA = decodeMapping(\"").append(mapping(casing.finalSigma()))
                .append("\");\n\n");

        caseRanges(out, "CASED", "Cased", casing.cased());
        caseRanges(out, "CASE_IGNORABLE", "Case_Ignorable", casing.caseIgnorable());

        paged(out, "LOWER", "For each code point, 0 where {@link #LOWER} maps it to itself, and otherwise one more"
                + " than where it is among {@link #LOWER}'s code points. No code point {@link #FINAL_SIGMA}"
                + " names is 0.", model.byCodePoint().lower(), true);
        paged(out, "UPPER", "For each code point, 0 where {@link #UPPER} maps it to itself, and otherwise one more"
                + " than where it is among {@link #UPPER}'s code points.", model.byCodePoint().upper(), true);

        out.append("}\n");
        return out.toString();
    }

    /**
     * {@code table} as two arrays, {@code <name>_BLOCKS} and {@code <name>_PAGES}: the value of
     * {@code cp} is at {@code (BLOCKS[cp >>> 8] & 0xFF) << 8 | cp & 0xFF} of the pages, a
     * {@code char} each where {@code wide} and a {@code byte} each otherwise. The decoders it
     * calls are {@link #PAGED_DECODERS}.
     */
    private static void paged(StringBuilder out, String name, String doc, PagedTable table,
                              boolean wide) {
        if (table.pages().size() > 0x100 || table.greatest() > (wide ? 0xFFFF : 0xFF)) {
            throw new IllegalStateException(name + " does not fit the arrays it is written as");
        }
        String shift = String.valueOf(PagedTable.SHIFT);
        String mask = "0x" + UcdModel.hex(PagedTable.PAGE - 1);
        docComment(out, doc + " Read as {@code " + name + "_PAGES[(" + name + "_BLOCKS[cp >>> " + shift
                + "] & 0xFF) << " + shift + " | cp & " + mask + "]}: each block of " + PagedTable.PAGE
                + " code points has the page of its values, and blocks that hold the same values share one ("
                + table.pages().size() + " pages).");
        out.append("    static final byte[] ").append(name).append("_BLOCKS = decodeBytes(")
                .append(literal(packed(table.blocks(), 2), "")).append(");\n");
        out.append("    static final ").append(wide ? "char" : "byte").append("[] ").append(name)
                .append("_PAGES = ").append(wide ? "decodeChars(" : "decodeBytes(")
                .append(literal(packed(table.values(), wide ? 4 : 2), "")).append(");\n\n");
    }

    private static final String PAGED_DECODERS = """
                /** Decodes a string of two hex digits a value into the bytes a code point indexes. */
                private static byte[] decodeBytes(String data) {
                    byte[] values = new byte[data.length() / 2];
                    for (int i = 0; i < values.length; i++) {
                        values[i] = (byte) Integer.parseInt(data, 2 * i, 2 * i + 2, 16);
                    }
                    return values;
                }

                /** Decodes a string of four hex digits a value into the chars a code point indexes. */
                private static char[] decodeChars(String data) {
                    char[] values = new char[data.length() / 4];
                    for (int i = 0; i < values.length; i++) {
                        values[i] = (char) Integer.parseInt(data, 4 * i, 4 * i + 4, 16);
                    }
                    return values;
                }

            """.stripIndent();

    private static void caseRanges(StringBuilder out, String name, String property, UcdModel.RangeSet ranges) {
        out.append("    /** {@code ").append(property).append("} (the property Unicode's {@code Final_Sigma}\n");
        out.append("     *  condition is stated over), as sorted non-overlapping inclusive ranges: index 0 is\n");
        out.append("     *  starts, index 1 is ends. */\n");
        out.append("    static final int[][] ").append(name).append(" = decodeRanges(\"")
                .append(ranges(ranges)).append("\");\n\n");
    }

    private static final String CASE_DECODERS = """
                /** A code point and the code point(s) it maps to — more than one for a Unicode
                 *  expansion such as {@code ß} → {@code SS}. */
                record Mapping(int[] codePoints, int[][] mapped) {}

                /** Decodes a "{@code <cp>:<mapped>[+<mapped>...] ...}" string — hex code points, space
                 *  separated entries, {@code +} joining a multi-code-point mapping — sorted by
                 *  {@code <cp>} so a lookup can binary search it. */
                private static Mapping decodeMapping(String data) {
                    String[] tokens = data.split(" ");
                    int[] codePoints = new int[tokens.length];
                    int[][] mapped = new int[tokens.length][];
                    for (int i = 0; i < tokens.length; i++) {
                        int colon = tokens[i].indexOf(':');
                        codePoints[i] = Integer.parseInt(tokens[i].substring(0, colon), 16);
                        String[] parts = tokens[i].substring(colon + 1).split("\\\\+");
                        int[] m = new int[parts.length];
                        for (int j = 0; j < parts.length; j++) {
                            m[j] = Integer.parseInt(parts[j], 16);
                        }
                        mapped[i] = m;
                    }
                    return new Mapping(codePoints, mapped);
                }

                /** Decodes a "{@code <start>-<end> ...}" string of sorted, non-overlapping inclusive
                 *  hex ranges into the parallel {@code [starts, ends]} arrays a lookup binary searches. */
                private static int[][] decodeRanges(String data) {
                    String[] tokens = data.split(" ");
                    int[] starts = new int[tokens.length];
                    int[] ends = new int[tokens.length];
                    for (int i = 0; i < tokens.length; i++) {
                        int dash = tokens[i].indexOf('-');
                        starts[i] = Integer.parseInt(tokens[i].substring(0, dash), 16);
                        ends[i] = Integer.parseInt(tokens[i].substring(dash + 1), 16);
                    }
                    return new int[][] {starts, ends};
                }

            """.stripIndent();

    // NormalizationTables

    private static String normalizationTables(UcdModel model) {
        String version = model.version();
        UcdModel.Decomposition decomposition = model.decomposition();
        StringBuilder out = new StringBuilder();
        out.append("package net.unit8.notation199x;\n\n");
        out.append("/**\n");
        out.append(" * The Unicode ").append(version)
                .append(" decomposition, combining class and script-specific composition\n");
        out.append(" * exclusion data {@link Normalization} reads, and for each form the bound below which text is\n");
        out.append(" * its own normalization.\n");
        out.append(" *\n");
        out.append(" * <p>Generated from Unicode ").append(version).append("'s {@code UnicodeData.txt},")
                .append(" {@code CompositionExclusions.txt}\n")
                .append(" * and {@code DerivedNormalizationProps.txt}'s quick checks")
                .append(" ({@code https://www.unicode.org/Public/").append(version).append("/ucd/})\n")
                .append(" * by {@code gen/Generate.java},")
                .append(" checked against {@code DerivedNormalizationProps.txt}'s\n")
                .append(" * {@code Full_Composition_Exclusion} at generation time.\n");
        out.append(" * DO NOT EDIT — regenerate on a Unicode version bump with")
                .append(" {@code java gen/Generate.java ucd/<version>},\n");
        out.append(" * which this file's source checksums let a reviewer confirm ran against the version it claims.\n");
        out.append(" *\n");
        out.append(" * <p>SHA-256, of the three input files as downloaded:\n");
        out.append(" * <ul>\n");
        for (String file : List.of("UnicodeData.txt", "CompositionExclusions.txt", "DerivedNormalizationProps.txt")) {
            out.append(" * <li>").append(file).append(": {@code ").append(model.sha256().get(file)).append("}\n");
        }
        out.append(" * </ul>\n");
        out.append(" */\n");
        out.append("final class NormalizationTables {\n\n");
        out.append("    private NormalizationTables() {}\n\n");
        out.append(NORMALIZATION_DECODERS);
        out.append(PAGED_DECODERS);

        UcdModel.CodePointMapping canonical = decomposition.canonical();
        out.append("    /** Unicode ").append(version).append("'s one-step canonical decomposition")
                .append(" mapping (").append(canonical.entries().size())
                .append(" code points); every other code point has none. A compatibility ({@code <tag>})")
                .append(" decomposition is a different question and is not here. */\n");
        out.append("    static final Mapping DECOMP = decodeMapping(").append(literal(mapping(canonical))).append(");\n\n");

        UcdModel.CodePointMapping compatibility = decomposition.compatibility();
        out.append("    /** Unicode ").append(version).append("'s one-step compatibility decomposition")
                .append(" mapping (").append(compatibility.entries().size())
                .append(" code points), the {@code <tag>} dropped. A code point is in this or in")
                .append(" {@link #DECOMP} or in neither. */\n");
        out.append("    static final Mapping COMPAT = decodeMapping(").append(literal(mapping(compatibility)))
                .append(");\n\n");

        paged(out, "CCC", "Unicode " + version + "'s canonical combining class of each code point, 0 for a"
                + " starter.", model.byCodePoint().combiningClass(), false);

        paged(out, "STABLE", "For each code point, the forms it is a stable starter in, a bit each: NFC 1, NFD 2,"
                + " NFKC 4 and NFKD 8. A stable starter is a starter whose quick check for the form is Yes. Text"
                + " made only of them is its own normalization in the form, and one of them ends what comes before"
                + " it: no mark after it is put in order before it or composes with a starter before it, and it"
                + " composes with nothing before it, since what does is Maybe, which the generator checks.", model.byCodePoint().stableStarters(), false);

        UcdModel.CodePoints exclusions = decomposition.scriptSpecificExclusions();
        out.append("    /** {@code CompositionExclusions.txt}'s script-specific exclusions (")
                .append(exclusions.members().size()).append(" code points) — the composition")
                .append(" eligibility {@code UnicodeData.txt} alone does not decide.")
                .append(" {@link Normalization#compose} folds the other two")
                .append(" {@code Full_Composition_Exclusion} categories (singleton and non-starter")
                .append(" decompositions) in from {@link #DECOMP}/{@link #CCC_PAGES} directly. */\n");
        out.append("    static final int[] SCRIPT_SPECIFIC_EXCLUSIONS = decodeSortedInts(\"")
                .append(hexList(exclusions.members())).append("\");\n\n");

        out.append("    /** For each form, the least code point that is not a starter or whose quick check for the")
                .append(" form is not Yes. Text made only of code points below it is its own normalization in")
                .append(" that form (UAX #15, the Detecting Normalization Forms section). */\n");
        decomposition.trivialLimits().forEach((form, limit) ->
                out.append("    static final int ").append(form).append("_TRIVIAL_LIMIT = 0x")
                        .append(UcdModel.hex(limit)).append(";\n"));

        out.append("}\n");
        return out.toString();
    }

    private static final String NORMALIZATION_DECODERS = """
                /** A code point and the code point(s) its decomposition maps to. */
                record Mapping(int[] codePoints, int[][] mapped) {}

                /** Decodes a "{@code <cp>:<mapped>[+<mapped>...] ...}" string — hex code points, space
                 *  separated entries, {@code +} joining a multi-code-point mapping — sorted by
                 *  {@code <cp>} so a lookup can binary search it. */
                private static Mapping decodeMapping(String data) {
                    String[] tokens = data.isEmpty() ? new String[0] : data.split(" ");
                    int[] codePoints = new int[tokens.length];
                    int[][] mapped = new int[tokens.length][];
                    for (int i = 0; i < tokens.length; i++) {
                        int colon = tokens[i].indexOf(':');
                        codePoints[i] = Integer.parseInt(tokens[i].substring(0, colon), 16);
                        String[] parts = tokens[i].substring(colon + 1).split("\\\\+");
                        int[] m = new int[parts.length];
                        for (int j = 0; j < parts.length; j++) {
                            m[j] = Integer.parseInt(parts[j], 16);
                        }
                        mapped[i] = m;
                    }
                    return new Mapping(codePoints, mapped);
                }

                /** Decodes a space-separated hex-code-point list, sorted, into the keys half of a
                 *  parallel-array lookup. */
                private static int[] decodeIntKeys(String data) {
                    return decodeSortedInts(data);
                }

                /** Decodes a space-separated hex-value list, in the same order as the keys it is
                 *  paired with — not sorted itself, since the sort is by key. */
                private static int[] decodeIntValues(String data) {
                    String[] tokens = data.isEmpty() ? new String[0] : data.split(" ");
                    int[] values = new int[tokens.length];
                    for (int i = 0; i < tokens.length; i++) {
                        values[i] = Integer.parseInt(tokens[i], 16);
                    }
                    return values;
                }

                /** Decodes a space-separated, ascending hex-code-point list into a sorted array a
                 *  lookup can binary search. */
                private static int[] decodeSortedInts(String data) {
                    return decodeIntValues(data);
                }

            """.stripIndent();

    // WhiteSpaceTables

    private static String whiteSpaceTables(UcdModel model) {
        StringBuilder out = new StringBuilder();
        out.append("package net.unit8.notation199x;\n\n");
        out.append("/**\n");
        out.append(" * The {@code White_Space} set {@link WhiteSpace} reads, as of Unicode ").append(model.version())
                .append(".\n");
        out.append(" *\n");
        provenance(out, model, "PropList.txt");
        out.append(" */\n");
        out.append("final class WhiteSpaceTables {\n\n");
        out.append("    private WhiteSpaceTables() {}\n\n");
        out.append(RANGES_DECODER);
        out.append("    /** {@code White_Space}, as sorted non-overlapping inclusive ranges: index 0 is starts,\n");
        out.append("     *  index 1 is ends. */\n");
        out.append("    static final int[][] WHITE_SPACE = decodeRanges(").append(literal(ranges(model.whiteSpace())))
                .append(");\n");
        out.append("}\n");
        return out.toString();
    }

    // PatternAlphabetTables

    private static String patternAlphabetTables(UcdModel model) {
        StringBuilder out = new StringBuilder();
        out.append("package net.unit8.notation199x.pattern;\n\n");
        out.append("/**\n");
        out.append(" * The characters {@link PatternAlphabet} keeps a backslash before for an escape: the letters and\n");
        out.append(" * the decimal digits, General_Category {@code L} and {@code Nd}, as of Unicode ")
                .append(model.version()).append(".\n");
        out.append(" *\n");
        provenance(out, model, "extracted/DerivedGeneralCategory.txt");
        out.append(" */\n");
        out.append("final class PatternAlphabetTables {\n\n");
        out.append("    private PatternAlphabetTables() {}\n\n");
        out.append(RANGES_DECODER);
        out.append("    /** The Unicode version the tables are read from. */\n");
        out.append("    static final String UNICODE_VERSION = \"").append(model.version()).append("\";\n\n");
        out.append("    /** General_Category {@code Lu}, {@code Ll}, {@code Lt}, {@code Lm}, {@code Lo} and\n");
        out.append("     *  {@code Nd}, as sorted non-overlapping inclusive ranges: index 0 is starts, index 1 is\n");
        out.append("     *  ends. */\n");
        out.append("    static final int[][] LETTERS_AND_DIGITS = decodeRanges(")
                .append(literal(ranges(model.patternEscapeAlphabet()))).append(");\n");
        out.append("}\n");
        return out.toString();
    }

    /** Which input a class is generated from, how to regenerate it, and the input's checksum. */
    private static void provenance(StringBuilder out, UcdModel model, String file) {
        String name = file.substring(file.lastIndexOf('/') + 1);
        out.append(" * <p>Generated from Unicode ").append(model.version()).append("'s {@code ").append(name)
                .append("} ({@code https://www.unicode.org/Public/").append(model.version()).append("/ucd/})\n");
        out.append(" * by {@code gen/Generate.java}. DO NOT EDIT — regenerate on a Unicode version bump with\n");
        out.append(" * {@code java gen/Generate.java ucd/<version>}.\n");
        out.append(" *\n");
        out.append(" * <p>SHA-256 of ").append(name).append(" as downloaded: {@code ").append(model.sha256().get(file))
                .append("}\n");
    }

    private static final String RANGES_DECODER = """
                /** Decodes a "{@code <start>-<end> ...}" string of sorted, non-overlapping inclusive
                 *  hex ranges into the parallel {@code [starts, ends]} arrays a lookup searches. */
                private static int[][] decodeRanges(String data) {
                    String[] tokens = data.split(" ");
                    int[] starts = new int[tokens.length];
                    int[] ends = new int[tokens.length];
                    for (int i = 0; i < tokens.length; i++) {
                        int dash = tokens[i].indexOf('-');
                        starts[i] = Integer.parseInt(tokens[i].substring(0, dash), 16);
                        ends[i] = Integer.parseInt(tokens[i].substring(dash + 1), 16);
                    }
                    return new int[][] {starts, ends};
                }

            """.stripIndent();

    // The data, as the decoders read it

    /** The most characters one string literal is given. A class file holds a constant string in at
     *  most 65,535 bytes (JVMS 4.4.7), and the data is ASCII. */
    private static final int LITERAL = 60_000;

    /** {@code data} as the Java expression for it: one string literal, or where it is longer than a
     *  class file holds in one constant, several cut between entries and joined again with the space
     *  that was between them. */
    private static String literal(String data) {
        return literal(data, " ");
    }

    /** {@code data} as the Java expression for it, cut where it is longer than one constant holds:
     *  at a space where {@code between} is one, which is joined in again, and anywhere where it is
     *  empty. */
    private static String literal(String data, String between) {
        if (data.length() <= LITERAL) {
            return '"' + data + '"';
        }
        StringBuilder out = new StringBuilder("String.join(\"").append(between).append('"');
        int from = 0;
        while (from < data.length()) {
            int to = data.length() - from <= LITERAL ? data.length()
                    : between.isEmpty() ? from + LITERAL : data.lastIndexOf(' ', from + LITERAL);
            out.append(",\n            \"").append(data, from, to).append('"');
            from = to + between.length();
        }
        return out.append(')').toString();
    }

    /** {@code text} as a doc comment of a member, broken between words before the hundredth column. */
    private static void docComment(StringBuilder out, String text) {
        StringBuilder line = new StringBuilder("    /**");
        for (String word : text.split(" ")) {
            if (line.length() + 1 + word.length() > 100) {
                out.append(line).append('\n');
                line = new StringBuilder("     *");
            }
            line.append(' ').append(word);
        }
        out.append(line).append(" */\n");
    }

    /** {@code values} as hex, {@code digits} digits each, one after another. */
    private static String packed(int[] values, int digits) {
        StringBuilder sb = new StringBuilder(values.length * digits);
        for (int value : values) {
            String hex = UcdModel.hex(value);
            sb.append("0".repeat(digits - hex.length())).append(hex);
        }
        return sb.toString();
    }

    /** {@code <cp>:<mapped>[+<mapped>...]}, space separated. */
    private static String mapping(UcdModel.CodePointMapping mapping) {
        StringBuilder sb = new StringBuilder();
        mapping.entries().forEach((cp, mapped) -> {
            if (!sb.isEmpty()) {
                sb.append(' ');
            }
            sb.append(UcdModel.hex(cp)).append(':');
            for (int i = 0; i < mapped.length; i++) {
                sb.append(i > 0 ? "+" : "").append(UcdModel.hex(mapped[i]));
            }
        });
        return sb.toString();
    }

    /** {@code <start>-<end>}, space separated. */
    private static String ranges(UcdModel.RangeSet ranges) {
        StringBuilder sb = new StringBuilder();
        for (int[] r : ranges.ranges()) {
            if (!sb.isEmpty()) {
                sb.append(' ');
            }
            sb.append(UcdModel.hex(r[0])).append('-').append(UcdModel.hex(r[1]));
        }
        return sb.toString();
    }

    /** {@code count} code points, in words. */
    private static String codePoints(int count) {
        return count + (count == 1 ? " code point" : " code points");
    }

    private static String hexList(Iterable<Integer> values) {
        StringBuilder sb = new StringBuilder();
        for (Integer value : values) {
            if (!sb.isEmpty()) {
                sb.append(' ');
            }
            sb.append(UcdModel.hex(value));
        }
        return sb.toString();
    }
}
