import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.SortedMap;
import java.util.SortedSet;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * What every language's tables are generated from: the Unicode Character Database of one pinned
 * version, read once, with what is derived from it and checked against what it publishes.
 *
 * <p>Building one is the whole of what the generator knows about Unicode. Every check it makes is
 * made here, and a model that was built has passed all of them: an input that does not open with
 * the version it is pinned to, a {@code SpecialCasing.txt} condition that is neither
 * {@code Final_Sigma} nor a language this has been told is tailoring, a derived
 * {@code Full_Composition_Exclusion} that is not the published one, a quick check value that is not
 * Yes, No or Maybe, or a quick check whose {@code @missing} line does not give every code point Yes
 * stops it rather than answering a plausible-looking wrong table. An emitter only writes what is
 * here in its language, and decides nothing about Unicode.
 *
 * @param version   the Unicode version every input states
 * @param sha256    the SHA-256 of each input, by its path under the UCD directory, in the order read
 * @param casing    what the default case conversion reads
 * @param decomposition what normalization reads
 * @param normalizationDerived what normalization works out from {@code decomposition}, worked out
 *                  here once so that an implementation whose run time would pay for working it
 *                  out again can hold it instead
 * @param whiteSpace {@code White_Space}
 * @param patternEscapeAlphabet the characters a pattern keeps a backslash before for an escape: the
 *                  letters and the decimal digits, General_Category {@code L} and {@code Nd}
 */
record UcdModel(String version, Map<String, String> sha256, Casing casing, Decomposition decomposition,
                NormalizationDerived normalizationDerived, RangeSet whiteSpace, RangeSet patternEscapeAlphabet) {

    /** The version every input is checked to be. */
    static final String VERSION = "18.0.0";

    /**
     * The untailored full case mapping and the condition {@code Final_Sigma} is stated over.
     *
     * @param lower         the full lowercase mapping: the {@code SpecialCasing.txt} one where it
     *                      states one, otherwise the simple one; a code point not here maps to itself
     * @param upper         the full uppercase mapping, the same way
     * @param finalSigma    the lowercase mapping that replaces {@code lower}'s at the end of a cased
     *                      run, at whatever arity it comes at
     * @param cased         {@code Cased}
     * @param caseIgnorable {@code Case_Ignorable}
     */
    record Casing(CodePointMapping lower, CodePointMapping upper, CodePointMapping finalSigma,
                  RangeSet cased, RangeSet caseIgnorable) {}

    /**
     * The one-step decompositions and combining classes, and what composition cannot read off them.
     *
     * @param canonical      the canonical decomposition mapping
     * @param compatibility  the compatibility decomposition mapping, its {@code <tag>} dropped
     * @param combiningClass the non-zero canonical combining classes
     * @param scriptSpecificExclusions the composition exclusions {@code CompositionExclusions.txt}
     *                       states outright
     * @param trivialLimits  for each form, the least code point that is not a starter or whose
     *                       quick check for the form is not Yes
     */
    record Decomposition(CodePointMapping canonical, CodePointMapping compatibility,
                         CodePointIntMapping combiningClass, CodePoints scriptSpecificExclusions,
                         SortedMap<String, Integer> trivialLimits) {}

    /**
     * What normalization works out from the decompositions and the combining classes, rather than
     * reads from the database: facts of the four forms an implementation may work out as it runs,
     * or hold as generated.
     *
     * @param compositions the primary composites, each by the two code points it is canonically
     *                     composed from: every two-member canonical decomposition whose first member
     *                     is a starter and that is not a script-specific exclusion. Hangul's, which
     *                     are arithmetic, are not here.
     */
    record NormalizationDerived(CodePointMapping compositions) {}

    /** A code point and the code points it maps to, by code point. */
    record CodePointMapping(SortedMap<Integer, int[]> entries) {}

    /** A code point and an integer, by code point. */
    record CodePointIntMapping(SortedMap<Integer, Integer> entries) {}

    /** Code points one by one, ascending. */
    record CodePoints(SortedSet<Integer> members) {}

    /**
     * Inclusive ranges, ascending and not overlapping, as the database lists them: two ranges that
     * meet are two ranges here if the file writes two lines.
     *
     * @param ranges each a {@code [start, end]} pair
     */
    record RangeSet(List<int[]> ranges) {

        RangeSet {
            for (int i = 1; i < ranges.size(); i++) {
                if (ranges.get(i)[0] <= ranges.get(i - 1)[1]) {
                    throw new IllegalStateException("range " + hex(ranges.get(i)[0]) + ".."
                            + hex(ranges.get(i)[1]) + " is not after the one before it");
                }
            }
        }
    }

    /**
     * Reads the model from {@code ucd}, the directory of the pinned version.
     *
     * @param ucd the directory, {@code ucd/18.0.0}
     * @return the model, every check passed
     */
    static UcdModel read(Path ucd) throws IOException {
        Inputs in = new Inputs(ucd);
        List<String> unicodeData = in.lines("UnicodeData.txt", null);
        List<String> specialCasing = in.lines("SpecialCasing.txt", "SpecialCasing");
        List<String> derivedCoreProperties = in.lines("DerivedCoreProperties.txt", "DerivedCoreProperties");
        List<String> compositionExclusions = in.lines("CompositionExclusions.txt", "CompositionExclusions");
        List<String> derivedNormalizationProps =
                in.lines("DerivedNormalizationProps.txt", "DerivedNormalizationProps");
        List<String> propList = in.lines("PropList.txt", "PropList");
        List<String> derivedGeneralCategory =
                in.lines("extracted/DerivedGeneralCategory.txt", "DerivedGeneralCategory");
        Decomposition decomposition = decomposition(unicodeData, compositionExclusions, derivedNormalizationProps);
        return new UcdModel(VERSION, Collections.unmodifiableMap(in.sha256),
                casing(unicodeData, specialCasing, derivedCoreProperties),
                decomposition, normalizationDerived(decomposition),
                ranges(propList, Set.of("White_Space")),
                ranges(derivedGeneralCategory, Set.of("Lu", "Ll", "Lt", "Lm", "Lo", "Nd")));
    }

    /** The ranges of the lines whose second field is one of {@code taken}, in order of code point.
     *  That field is a binary property in {@code PropList.txt} and a value of General_Category in
     *  {@code DerivedGeneralCategory.txt}, which lists its values one after another, so ranges of
     *  different values are put back in order here. */
    private static RangeSet ranges(List<String> lines, Set<String> taken) {
        List<int[]> ranges = new ArrayList<>();
        for (PropertyLine line : PropertyLine.read(lines, false)) {
            if (taken.contains(line.property()) && line.value().isEmpty()) {
                ranges.add(new int[] {line.start(), line.end()});
            }
        }
        ranges.sort(Comparator.comparingInt(range -> range[0]));
        return new RangeSet(ranges);
    }

    /** The inputs read so far, and their checksums. */
    private static final class Inputs {

        private final Path ucd;
        private final Map<String, String> sha256 = new LinkedHashMap<>();

        Inputs(Path ucd) {
            this.ucd = ucd;
        }

        /** The lines of {@code file}, checked to open with {@code # <header>-<version>.txt} where
         *  {@code header} is given. {@code UnicodeData.txt} has no header of its own. A checksum
         *  alone does not catch the directory being another version than the one pinned: it proves
         *  the bytes are what was hashed, not that they are the version they are labelled. */
        List<String> lines(String file, String header) throws IOException {
            byte[] bytes = Files.readAllBytes(ucd.resolve(file));
            sha256.put(file, checksum(bytes));
            List<String> lines = new String(bytes, StandardCharsets.UTF_8).lines().toList();
            if (header != null) {
                String expected = "# " + header + "-" + VERSION + ".txt";
                if (!lines.get(0).equals(expected)) {
                    throw new IllegalStateException(
                            ucd.resolve(file) + " does not open with " + expected + " (found: " + lines.get(0)
                                    + ") — this generator is pinned to Unicode " + VERSION + "; update"
                                    + " VERSION and re-verify every witness before regenerating"
                                    + " against a different one");
                }
            }
            return lines;
        }
    }

    // Case conversion

    private static Casing casing(List<String> unicodeData, List<String> specialCasing,
                                 List<String> derivedCoreProperties) {
        Map<Integer, Integer> simpleLower = new TreeMap<>();
        Map<Integer, Integer> simpleUpper = new TreeMap<>();
        for (String line : unicodeData) {
            if (line.isBlank()) {
                continue;
            }
            // Fields 12 and 13 are the simple uppercase and lowercase mappings: one to one and
            // independent of context and language, which is why SpecialCasing.txt calls out
            // everything wider than that on its own.
            String[] f = line.split(";", -1);
            int cp = Integer.parseInt(f[0], 16);
            if (!f[12].isBlank()) {
                simpleUpper.put(cp, Integer.parseInt(f[12].trim(), 16));
            }
            if (!f[13].isBlank()) {
                simpleLower.put(cp, Integer.parseInt(f[13].trim(), 16));
            }
        }

        SortedMap<Integer, int[]> fullLower = new TreeMap<>();
        SortedMap<Integer, int[]> fullUpper = new TreeMap<>();
        SortedMap<Integer, int[]> finalSigma = new TreeMap<>();
        for (String rawLine : specialCasing) {
            String line = rawLine.replaceFirst("#.*", "");
            if (line.isBlank()) {
                continue;
            }
            // <code>; <lower>; <title>; <upper>; (<condition_list>;)?
            String[] f = line.split(";", -1);
            int cp = Integer.parseInt(f[0].trim(), 16);
            String condition = f.length > 4 ? f[4].trim() : "";
            if (condition.isEmpty()) {
                fullLower.put(cp, codePoints(f[1]));
                fullUpper.put(cp, codePoints(f[3]));
            } else if (condition.equalsIgnoreCase("Final_Sigma")) {
                finalSigma.put(cp, codePoints(f[1]));
            } else if (KNOWN_TAILORING_LANGUAGES.contains(firstWord(condition).toLowerCase(Locale.ROOT))) {
                // Locale tailoring, outside the untailored conversion: read and discarded.
            } else {
                throw new IllegalStateException(
                        "unrecognized SpecialCasing.txt condition \"" + condition + "\" at U+"
                                + hex(cp) + " — is this a new untailored context Unicode "
                                + VERSION + " added, or a language this generator's"
                                + " KNOWN_TAILORING_LANGUAGES does not list yet? Decide which before"
                                + " teaching the generator to handle it either way.");
            }
        }

        List<int[]> cased = new ArrayList<>();
        List<int[]> caseIgnorable = new ArrayList<>();
        for (PropertyLine line : PropertyLine.read(derivedCoreProperties, false)) {
            if (line.property().equals("Cased")) {
                cased.add(new int[] {line.start(), line.end()});
            } else if (line.property().equals("Case_Ignorable")) {
                caseIgnorable.add(new int[] {line.start(), line.end()});
            }
        }

        return new Casing(merge(simpleLower, fullLower), merge(simpleUpper, fullUpper),
                new CodePointMapping(finalSigma), new RangeSet(cased), new RangeSet(caseIgnorable));
    }

    /** Condition lists checked to be locale tailoring rather than merely unrecognized.
     *  {@code SpecialCasing.txt} documents a condition list as "language IDs or casing contexts" and
     *  says to expect more of either in a later version; a language may be added here, but a new
     *  language-insensitive context, the {@code Final_Sigma} kind, is exactly what must not fall
     *  into this set by default. */
    private static final Set<String> KNOWN_TAILORING_LANGUAGES = Set.of("lt", "tr", "az");

    private static String firstWord(String s) {
        int space = s.indexOf(' ');
        return space < 0 ? s : s.substring(0, space);
    }

    /** The full mapping where one is stated, otherwise the simple one. */
    private static CodePointMapping merge(Map<Integer, Integer> simple, Map<Integer, int[]> full) {
        SortedMap<Integer, int[]> merged = new TreeMap<>();
        simple.forEach((cp, mapped) -> merged.put(cp, new int[] {mapped}));
        merged.putAll(full);
        return new CodePointMapping(merged);
    }

    // Normalization

    private static Decomposition decomposition(List<String> unicodeData, List<String> compositionExclusions,
                                               List<String> derivedNormalizationProps) {
        SortedMap<Integer, Integer> ccc = new TreeMap<>();
        SortedMap<Integer, int[]> canonical = new TreeMap<>();
        SortedMap<Integer, int[]> compatibility = new TreeMap<>();
        for (String line : unicodeData) {
            if (line.isBlank()) {
                continue;
            }
            // Field 3 is the canonical combining class, 5 the decomposition mapping: canonical where
            // it does not start with <tag>, compatibility, read without its tag, where it does.
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
                canonical.put(cp, codePoints(decomposition));
            } else {
                compatibility.put(cp, codePoints(decomposition.substring(decomposition.indexOf('>') + 1)));
            }
        }

        SortedSet<Integer> scriptSpecific = new TreeSet<>();
        for (String rawLine : compositionExclusions) {
            String line = rawLine.replaceFirst("#.*", "").trim();
            if (!line.isEmpty()) {
                scriptSpecific.add(Integer.parseInt(line, 16));
            }
        }

        // Full_Composition_Exclusion is the script-specific exclusions, the singleton
        // decompositions and the ones whose first member is not a starter. Normalization derives the
        // last two from the canonical decompositions and combining classes as it runs, so the
        // derivation is checked here against what Unicode publishes for the same version.
        Set<Integer> derived = new TreeSet<>(scriptSpecific);
        canonical.forEach((cp, mapped) -> {
            if (mapped.length == 1 || ccc.getOrDefault(mapped[0], 0) != 0) {
                derived.add(cp);
            }
        });
        List<PropertyLine> lines = PropertyLine.read(derivedNormalizationProps, false);
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
        if (!derived.equals(published)) {
            Set<Integer> missing = new TreeSet<>(published);
            missing.removeAll(derived);
            Set<Integer> extra = new TreeSet<>(derived);
            extra.removeAll(published);
            throw new IllegalStateException(
                    "derived Full_Composition_Exclusion disagrees with DerivedNormalizationProps.txt"
                            + " — missing " + hexSet(missing) + ", extra " + hexSet(extra)
                            + " — the singleton/non-starter derivation is wrong, not the published"
                            + " property");
        }

        List<PropertyLine> missingLines = PropertyLine.read(derivedNormalizationProps, true);
        SortedMap<String, Integer> trivialLimits = new TreeMap<>();
        for (String form : List.of("NFC", "NFD", "NFKC", "NFKD")) {
            trivialLimits.put(form, Math.min(ccc.firstKey(),
                    firstNotQuickCheckYes(form + "_QC", lines, missingLines)));
        }

        return new Decomposition(new CodePointMapping(canonical), new CodePointMapping(compatibility),
                new CodePointIntMapping(ccc), new CodePoints(scriptSpecific), trivialLimits);
    }

    /** What normalization works out from {@code decomposition}. The composition rule is the one
     *  Full_Composition_Exclusion was checked against above: a singleton, a decomposition whose
     *  first member is not a starter and a script-specific exclusion are no composite. */
    private static NormalizationDerived normalizationDerived(Decomposition decomposition) {
        SortedMap<Integer, Integer> ccc = decomposition.combiningClass().entries();
        SortedSet<Integer> excluded = decomposition.scriptSpecificExclusions().members();
        SortedMap<Integer, int[]> compositions = new TreeMap<>();
        Map<List<Integer>, Integer> byPair = new LinkedHashMap<>();
        decomposition.canonical().entries().forEach((cp, mapped) -> {
            if (mapped.length == 2 && ccc.getOrDefault(mapped[0], 0) == 0 && !excluded.contains(cp)) {
                Integer other = byPair.put(List.of(mapped[0], mapped[1]), cp);
                if (other != null) {
                    throw new IllegalStateException(hex(other) + " and " + hex(cp) + " are both composed from "
                            + hex(mapped[0]) + " " + hex(mapped[1]));
                }
                compositions.put(cp, mapped);
            }
        });
        return new NormalizationDerived(new CodePointMapping(compositions));
    }

    /** A quick check's values, by their short and long names in {@code PropertyValueAliases.txt}. */
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

    /** The least code point whose quick check {@code property} is not Yes. That is the least one a
     *  line gives No or Maybe only where every code point no line names is Yes, so the
     *  {@code @missing} line has to say so over the whole code space. */
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

    // The file format

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

    private static int[] codePoints(String hexList) {
        String[] parts = hexList.trim().split("\\s+");
        int[] result = new int[parts.length];
        for (int i = 0; i < parts.length; i++) {
            result[i] = Integer.parseInt(parts[i], 16);
        }
        return result;
    }

    private static String checksum(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("every Java platform has SHA-256", e);
        }
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

    static String hex(int v) {
        return Integer.toHexString(v).toUpperCase(Locale.ROOT);
    }
}
