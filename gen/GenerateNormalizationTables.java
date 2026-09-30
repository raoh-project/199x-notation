import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * Regenerates {@code java/src/main/java/net/unit8/notation199x/NormalizationTables.java} from the Unicode
 * Character Database.
 *
 * <p>Reads {@code UnicodeData.txt} and {@code CompositionExclusions.txt} for one pinned Unicode
 * version and emits four tables: the one-step canonical decomposition mapping, the one-step
 * compatibility decomposition mapping (the ones written with a {@code <tag>}, the tag dropped), the
 * non-zero canonical combining classes, and the composition exclusions
 * {@code CompositionExclusions.txt} states outright (the ones "script specific" enough that nothing
 * in {@code UnicodeData.txt} implies them). Beside them it emits one bound for each form,
 * {@code NFC_TRIVIAL_LIMIT} and its three siblings: every code point below it is a starter whose
 * quick check for the form is Yes, which is UAX #15's condition for text made of such code points
 * to be its own normalization in that form.
 *
 * <p>Composition is not a fourth table. Two of the three ways a code point is excluded from
 * composing — its canonical decomposition has one member (a singleton), or the first member is
 * not a starter (non-zero combining class) — are exactly what {@code Normalization.compose} reads off
 * {@code DECOMP} and {@code CCC} already; storing a derived composition table beside the
 * decomposition table it was derived from would let a hand slip and the two disagree. Composing
 * from decomposition, at the one place that reads both, is what keeps that impossible.
 *
 * <p>Reads two properties of {@code DerivedNormalizationProps.txt}, for two different things.
 * {@code Full_Composition_Exclusion} checks this generator's own derivation — script-specific
 * exclusions plus singleton and non-starter decompositions, recomputed here — against what Unicode
 * publishes for the same version; nothing it says reaches {@code NormalizationTables.java}.
 * The four quick checks ({@code NFC_QC}, {@code NFD_QC}, {@code NFKC_QC}, {@code NFKD_QC}) are read
 * rather than derived from the tables here, since they are Unicode's own answer to the question a
 * bound asks; the first code point of each that is not Yes bounds that form's limit.
 *
 * <p>Fails closed: a version header that does not read Unicode {@link #UNICODE_VERSION}, a
 * derived exclusion set that does not exactly match {@code Full_Composition_Exclusion}, a value
 * given to that binary property, a quick check value that is not Yes, No or Maybe, or a quick
 * check's {@code @missing} line that does not give every code point Yes stops the run rather than
 * emitting a plausible-looking wrong table.
 *
 * <p>Not part of the Maven build, for the reason {@code GenerateCaseTables.java} gives: a Unicode
 * version bump is a specification change, not a dependency bump. Run from the repository root:
 *
 * <pre>java gen/GenerateNormalizationTables.java ucd/18.0.0</pre>
 *
 * <p>{@code NormalizationTest.txt}, beside the three files above, is not read here; the Java tests
 * run it against the generated tables.
 */
public final class GenerateNormalizationTables {

    private static final Path OUTPUT =
            Path.of("java/src/main/java/net/unit8/notation199x/NormalizationTables.java");
    private static final String UNICODE_VERSION = "18.0.0";

    private GenerateNormalizationTables() {}

    public static void main(String[] args) throws IOException, NoSuchAlgorithmException {
        if (args.length != 1) {
            System.err.println("usage: java gen/GenerateNormalizationTables.java <ucd-directory>");
            System.exit(1);
        }
        Path ucd = Path.of(args[0]);
        Path unicodeData = ucd.resolve("UnicodeData.txt");
        Path compositionExclusions = ucd.resolve("CompositionExclusions.txt");
        Path derivedNormalizationProps = ucd.resolve("DerivedNormalizationProps.txt");

        checkVersionHeader(compositionExclusions, "CompositionExclusions");
        checkVersionHeader(derivedNormalizationProps, "DerivedNormalizationProps");

        Map<Integer, Integer> ccc = new TreeMap<>();
        Map<Integer, int[]> decomp = new TreeMap<>();
        Map<Integer, int[]> compat = new TreeMap<>();
        parseUnicodeData(unicodeData, ccc, decomp, compat);

        Set<Integer> scriptSpecific = parseCompositionExclusions(compositionExclusions);
        Set<Integer> derivedFull = deriveFullExclusion(decomp, ccc, scriptSpecific);
        List<String> normalizationProps = Files.readAllLines(derivedNormalizationProps, StandardCharsets.UTF_8);
        List<PropertyLine> propertyLines = PropertyLine.read(normalizationProps, false);
        Set<Integer> published = fullCompositionExclusion(propertyLines);
        if (!derivedFull.equals(published)) {
            Set<Integer> missing = new TreeSet<>(published);
            missing.removeAll(derivedFull);
            Set<Integer> extra = new TreeSet<>(derivedFull);
            extra.removeAll(published);
            throw new IllegalStateException(
                    "derived Full_Composition_Exclusion disagrees with " + derivedNormalizationProps
                            + " — missing " + hexSet(missing) + ", extra " + hexSet(extra)
                            + " — this generator's singleton/non-starter derivation is wrong, not the"
                            + " published property");
        }

        List<PropertyLine> missingLines = PropertyLine.read(normalizationProps, true);
        Map<String, Integer> trivialLimits = new TreeMap<>();
        for (String form : List.of("NFC", "NFD", "NFKC", "NFKD")) {
            trivialLimits.put(form, Math.min(Collections.min(ccc.keySet()),
                    firstNotQuickCheckYes(form + "_QC", propertyLines, missingLines)));
        }

        String source = render(decomp, compat, ccc, scriptSpecific, trivialLimits,
                checksum(unicodeData), checksum(compositionExclusions), checksum(derivedNormalizationProps));
        Files.writeString(OUTPUT, source, StandardCharsets.UTF_8);
        System.out.println("wrote " + OUTPUT + " (" + decomp.size() + " canonical and " + compat.size()
                + " compatibility decompositions, " + ccc.size()
                + " non-zero combining classes, " + scriptSpecific.size() + " script-specific exclusions,"
                + " verified against " + published.size() + " published Full_Composition_Exclusion entries,"
                + " trivial limits " + trivialLimits + ")");
    }

    /** Neither file states its own version in {@code UnicodeData.txt}'s bare-line format, but both
     *  files this generator reads besides it are a {@code # <FileName>-<version>.txt} first line. */
    private static void checkVersionHeader(Path path, String fileName) throws IOException {
        String firstLine = Files.readAllLines(path, StandardCharsets.UTF_8).get(0);
        String expected = "# " + fileName + "-" + UNICODE_VERSION + ".txt";
        if (!firstLine.equals(expected)) {
            throw new IllegalStateException(
                    path + " does not open with " + expected + " (found: " + firstLine + ") — this"
                            + " generator is pinned to Unicode " + UNICODE_VERSION + "; update"
                            + " UNICODE_VERSION and re-verify every witness before regenerating"
                            + " against a different one");
        }
    }

    /** {@code UnicodeData.txt} fields, 0-indexed: 3 is the canonical combining class, 5 is the
     *  decomposition mapping — present and NOT starting with {@code <} is a canonical decomposition;
     *  present and starting with {@code <tag>} is a compatibility one, read without its tag. */
    private static void parseUnicodeData(Path path, Map<Integer, Integer> ccc, Map<Integer, int[]> decomp,
            Map<Integer, int[]> compat) throws IOException {
        for (String line : Files.readAllLines(path, StandardCharsets.UTF_8)) {
            if (line.isBlank()) {
                continue;
            }
            String[] f = line.split(";", -1);
            int cp = Integer.parseInt(f[0], 16);
            int cccValue = Integer.parseInt(f[3]);
            if (cccValue != 0) {
                ccc.put(cp, cccValue);
            }
            String decomposition = f[5].trim();
            if (decomposition.isEmpty()) {
                continue;
            }
            if (decomposition.charAt(0) != '<') {
                decomp.put(cp, codePoints(decomposition));
            } else {
                compat.put(cp, codePoints(decomposition.substring(decomposition.indexOf('>') + 1).trim()));
            }
        }
    }

    private static int[] codePoints(String hexList) {
        String[] parts = hexList.split("\\s+");
        int[] result = new int[parts.length];
        for (int i = 0; i < parts.length; i++) {
            result[i] = Integer.parseInt(parts[i], 16);
        }
        return result;
    }

    /** The bare hex-code-point lines before the first blank/comment-only section this file's
     *  header explains as "cannot be derived from {@code UnicodeData.txt}" — every other line is
     *  a comment, including the four non-starter examples quoted for reference near the end,
     *  which a bare {@code #} prefix already excludes. */
    private static Set<Integer> parseCompositionExclusions(Path path) throws IOException {
        Set<Integer> exclusions = new TreeSet<>();
        for (String rawLine : Files.readAllLines(path, StandardCharsets.UTF_8)) {
            String line = rawLine.replaceFirst("#.*", "").trim();
            if (line.isEmpty()) {
                continue;
            }
            exclusions.add(Integer.parseInt(line, 16));
        }
        return exclusions;
    }

    /** {@code Full_Composition_Exclusion} = script-specific exclusions, plus every code point
     *  whose canonical decomposition is a singleton, plus every code point whose canonical
     *  decomposition's first member is not a starter (spec: {@code CompositionExclusions.txt}'s
     *  own header, "Generated from: Composition Exclusions + Singletons + Non-Starter
     *  Decompositions"). Recomputing the last two here rather than reading them off
     *  {@code DerivedNormalizationProps.txt} is the point: {@code Normalization.compose} derives
     *  composition eligibility from {@code DECOMP}/{@code CCC} the same way at run time, so the
     *  two cannot disagree — this method is what proves the derivation rule itself is right,
     *  against Unicode's own published answer. */
    private static Set<Integer> deriveFullExclusion(
            Map<Integer, int[]> decomp, Map<Integer, Integer> ccc, Set<Integer> scriptSpecific) {
        Set<Integer> full = new TreeSet<>(scriptSpecific);
        for (Map.Entry<Integer, int[]> e : decomp.entrySet()) {
            int[] mapped = e.getValue();
            if (mapped.length == 1 || ccc.getOrDefault(mapped[0], 0) != 0) {
                full.add(e.getKey());
            }
        }
        return full;
    }

    /**
     * One line of a UCD property file (UAX #44, the File Format Conventions section):
     * {@code <range-or-code-point> ; <property> [; <value>]}. A binary property has no value field,
     * and a line names a code point only where the property is true of it. Any other property has a
     * value field, and a code point no line names has the value its {@code @missing} line states.
     */
    private record PropertyLine(int start, int end, String property, String value) {

        private static final String MISSING = "# @missing:";

        /** The data lines of {@code lines}; {@code missing} true reads the {@code @missing} lines
         *  instead, which are comments to everything else. */
        static List<PropertyLine> read(List<String> lines, boolean missing) {
            List<PropertyLine> read = new ArrayList<>();
            for (String rawLine : lines) {
                if (missing != rawLine.startsWith(MISSING)) {
                    continue;
                }
                String line = (missing ? rawLine.substring(MISSING.length()) : rawLine).replaceFirst("#.*", "").trim();
                if (line.isEmpty()) {
                    continue;
                }
                String[] f = line.split(";", -1);
                if (f.length != 2 && f.length != 3) {
                    throw new IllegalStateException("not a property line: \"" + rawLine + "\"");
                }
                String range = f[0].trim();
                int dots = range.indexOf("..");
                int start = Integer.parseInt(dots >= 0 ? range.substring(0, dots) : range, 16);
                int end = dots >= 0 ? Integer.parseInt(range.substring(dots + 2), 16) : start;
                read.add(new PropertyLine(start, end, f[1].trim(), f.length == 3 ? f[2].trim() : ""));
            }
            return read;
        }
    }

    /** The code points {@code Full_Composition_Exclusion}, a binary property, is true of. This file
     *  states many derived properties; every line for a different one is skipped. */
    private static Set<Integer> fullCompositionExclusion(List<PropertyLine> lines) {
        Set<Integer> published = new TreeSet<>();
        for (PropertyLine line : lines) {
            if (!line.property().equals("Full_Composition_Exclusion")) {
                continue;
            }
            if (!line.value().isEmpty()) {
                throw new IllegalStateException("Full_Composition_Exclusion is binary, but a line gives it"
                        + " the value \"" + line.value() + "\"");
            }
            for (int cp = line.start(); cp <= line.end(); cp++) {
                published.add(cp);
            }
        }
        return published;
    }

    /** A quick check's values, by their short and long names in
     *  {@code PropertyValueAliases.txt}. */
    private enum QuickCheck {
        YES, NO, MAYBE;

        static QuickCheck of(String value) {
            return switch (value) {
                case "Y", "Yes" -> YES;
                case "N", "No" -> NO;
                case "M", "Maybe" -> MAYBE;
                default -> throw new IllegalStateException(
                        "quick check value \"" + value + "\" is none of Yes, No and Maybe");
            };
        }
    }

    /** The least code point whose quick check {@code property} is not Yes. That is the least one a line
     *  gives No or Maybe only where every code point no line names is Yes, so the {@code @missing}
     *  line has to say so over the whole code space. */
    private static int firstNotQuickCheckYes(String property, List<PropertyLine> lines,
            List<PropertyLine> missing) {
        List<PropertyLine> defaults = missing.stream().filter(line -> line.property().equals(property)).toList();
        if (defaults.size() != 1 || defaults.get(0).start() != 0 || defaults.get(0).end() != Character.MAX_CODE_POINT
                || QuickCheck.of(defaults.get(0).value()) != QuickCheck.YES) {
            throw new IllegalStateException(property + "'s @missing lines are " + defaults + ", not one line giving"
                    + " every code point Yes — the least code point that is not Yes is then not read off"
                    + " the lines that name one");
        }
        int first = Integer.MAX_VALUE;
        for (PropertyLine line : lines) {
            if (line.property().equals(property) && QuickCheck.of(line.value()) != QuickCheck.YES) {
                first = Math.min(first, line.start());
            }
        }
        if (first == Integer.MAX_VALUE) {
            throw new IllegalStateException("no " + property + " line gives No or Maybe — not the file this generator"
                    + " reads");
        }
        return first;
    }

    private static String hexSet(Set<Integer> cps) {
        StringBuilder sb = new StringBuilder("[");
        for (Integer cp : cps) {
            if (sb.length() > 1) {
                sb.append(' ');
            }
            sb.append(hex(cp));
        }
        return sb.append(']').toString();
    }

    private static String checksum(Path path) throws IOException, NoSuchAlgorithmException {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        byte[] hash = digest.digest(Files.readAllBytes(path));
        StringBuilder hex = new StringBuilder();
        for (byte b : hash) {
            hex.append(String.format("%02x", b));
        }
        return hex.toString();
    }

    private static String render(Map<Integer, int[]> decomp, Map<Integer, int[]> compat,
            Map<Integer, Integer> ccc, Set<Integer> scriptSpecificExclusions,
            Map<String, Integer> trivialLimits, String unicodeDataSha256,
            String compositionExclusionsSha256, String derivedNormalizationPropsSha256) {
        StringBuilder out = new StringBuilder();
        out.append("package net.unit8.notation199x;\n\n");
        out.append("/**\n");
        out.append(" * The Unicode ").append(UNICODE_VERSION)
                .append(" decomposition, combining class and script-specific composition\n");
        out.append(" * exclusion data {@link Normalization} reads, and for each form the bound below which text is\n");
        out.append(" * its own normalization.\n");
        out.append(" *\n");
        out.append(" * <p>Generated from Unicode ").append(UNICODE_VERSION).append("'s {@code UnicodeData.txt},")
                .append(" {@code CompositionExclusions.txt}\n")
                .append(" * and {@code DerivedNormalizationProps.txt}'s quick checks")
                .append(" ({@code https://www.unicode.org/Public/").append(UNICODE_VERSION).append("/ucd/})\n")
                .append(" * by {@code gen/GenerateNormalizationTables.java},")
                .append(" checked against {@code DerivedNormalizationProps.txt}'s\n")
                .append(" * {@code Full_Composition_Exclusion} at generation time.\n");
        out.append(" * DO NOT EDIT — regenerate on a Unicode version bump with")
                .append(" {@code java gen/GenerateNormalizationTables.java ucd/<version>},\n");
        out.append(" * which this file's source checksums let a reviewer confirm ran against the version it claims.\n");
        out.append(" *\n");
        out.append(" * <p>SHA-256, of the three input files as downloaded:\n");
        out.append(" * <ul>\n");
        out.append(" * <li>UnicodeData.txt: {@code ").append(unicodeDataSha256).append("}\n");
        out.append(" * <li>CompositionExclusions.txt: {@code ").append(compositionExclusionsSha256).append("}\n");
        out.append(" * <li>DerivedNormalizationProps.txt: {@code ")
                .append(derivedNormalizationPropsSha256).append("}\n");
        out.append(" * </ul>\n");
        out.append(" */\n");
        out.append("final class NormalizationTables {\n\n");
        out.append("    private NormalizationTables() {}\n\n");
        out.append(DECODER_SOURCE);

        out.append("    /** Unicode ").append(UNICODE_VERSION).append("'s one-step canonical decomposition")
                .append(" mapping (").append(decomp.size())
                .append(" code points); every other code point has none. A compatibility ({@code <tag>})")
                .append(" decomposition is a different question and is not here. */\n");
        out.append("    static final Mapping DECOMP = decodeMapping(").append(literal(encodeMapping(decomp))).append(");\n\n");

        out.append("    /** Unicode ").append(UNICODE_VERSION).append("'s one-step compatibility decomposition")
                .append(" mapping (").append(compat.size())
                .append(" code points), the {@code <tag>} dropped. A code point is in this or in")
                .append(" {@link #DECOMP} or in neither. */\n");
        out.append("    static final Mapping COMPAT = decodeMapping(").append(literal(encodeMapping(compat))).append(");\n\n");

        out.append("    /** Unicode ").append(UNICODE_VERSION).append("'s non-zero canonical combining classes (")
                .append(ccc.size()).append(" code points); every other code point's is 0 — a starter. */\n");
        out.append("    static final int[] CCC_KEYS = decodeIntKeys(\"").append(encodeCccKeys(ccc)).append("\");\n");
        out.append("    static final int[] CCC_VALUES = decodeIntValues(\"").append(encodeCccValues(ccc)).append("\");\n\n");

        out.append("    /** {@code CompositionExclusions.txt}'s script-specific exclusions (")
                .append(scriptSpecificExclusions.size()).append(" code points) — the composition")
                .append(" eligibility {@code UnicodeData.txt} alone does not decide.")
                .append(" {@link Normalization#compose} folds the other two")
                .append(" {@code Full_Composition_Exclusion} categories (singleton and non-starter")
                .append(" decompositions) in from {@link #DECOMP}/{@link #CCC_KEYS} directly. */\n");
        out.append("    static final int[] SCRIPT_SPECIFIC_EXCLUSIONS = decodeSortedInts(\"")
                .append(encodeSortedInts(scriptSpecificExclusions)).append("\");\n\n");

        out.append("    /** For each form, the least code point that is not a starter or whose quick check for the")
                .append(" form is not Yes. Text made only of code points below it is its own normalization in")
                .append(" that form (UAX #15, the Detecting Normalization Forms section). */\n");
        for (Map.Entry<String, Integer> limit : trivialLimits.entrySet()) {
            out.append("    static final int ").append(limit.getKey()).append("_TRIVIAL_LIMIT = 0x")
                    .append(hex(limit.getValue())).append(";\n");
        }

        out.append("}\n");
        return out.toString();
    }

    /** Kept as ordinary, reviewed Java rather than generated, for the reason
     *  {@code GenerateCaseTables.java}'s decoder is: a literal large enough to hold this data does
     *  not fit the JVM's 64&nbsp;KB per-method bytecode limit as an array initializer, so the data
     *  is a compact string constant, decoded once at class load. */
    private static final String DECODER_SOURCE = """
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

    /** The most characters one string literal is given. A class file holds a constant string in at
     *  most 65,535 bytes (JVMS 4.4.7), and the data is ASCII. */
    private static final int LITERAL = 60_000;

    /** {@code data} as the Java expression for it: one string literal, or where it is longer than a
     *  class file holds in one constant, several cut between entries and joined again with the space
     *  that was between them. */
    private static String literal(String data) {
        if (data.length() <= LITERAL) {
            return '"' + data + '"';
        }
        StringBuilder out = new StringBuilder("String.join(\" \"");
        int from = 0;
        while (from < data.length()) {
            int to = data.length() - from <= LITERAL ? data.length() : data.lastIndexOf(' ', from + LITERAL);
            out.append(",\n            \"").append(data, from, to).append('"');
            from = to + 1;
        }
        return out.append(')').toString();
    }

    private static String encodeMapping(Map<Integer, int[]> mapping) {
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<Integer, int[]> e : mapping.entrySet()) {
            if (!sb.isEmpty()) {
                sb.append(' ');
            }
            sb.append(hex(e.getKey())).append(':').append(joinHex(e.getValue(), "+"));
        }
        return sb.toString();
    }

    private static String encodeCccKeys(Map<Integer, Integer> ccc) {
        StringBuilder sb = new StringBuilder();
        for (Integer cp : ccc.keySet()) {
            if (!sb.isEmpty()) {
                sb.append(' ');
            }
            sb.append(hex(cp));
        }
        return sb.toString();
    }

    private static String encodeCccValues(Map<Integer, Integer> ccc) {
        StringBuilder sb = new StringBuilder();
        for (Integer value : ccc.values()) {
            if (!sb.isEmpty()) {
                sb.append(' ');
            }
            sb.append(hex(value));
        }
        return sb.toString();
    }

    private static String encodeSortedInts(Set<Integer> cps) {
        StringBuilder sb = new StringBuilder();
        for (Integer cp : cps) {
            if (!sb.isEmpty()) {
                sb.append(' ');
            }
            sb.append(hex(cp));
        }
        return sb.toString();
    }

    private static String hex(int v) {
        return Integer.toHexString(v).toUpperCase(Locale.ROOT);
    }

    private static String joinHex(int[] values, String sep) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < values.length; i++) {
            if (i > 0) {
                sb.append(sep);
            }
            sb.append(hex(values[i]));
        }
        return sb.toString();
    }
}
