import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.SortedMap;

/**
 * Writes a {@link UcdModel} as the Go sources {@code go/} reads.
 *
 * <p>What is decided here is how Go holds the data: which file each table is in, the comment it
 * carries, and the composite literal each is written as. A Go composite literal of constants is laid
 * out by the linker and decoded by nothing at run time, so every table is written as the values it
 * holds, in the form {@code gofmt} leaves as it is. Nothing here is about Unicode.
 */
final class GoEmitter {

    private static final String SOURCES = "go/";

    private GoEmitter() {}

    /**
     * Every source the model is written as, by where it goes.
     *
     * @param model the model
     * @return the path of each source, from the repository root, and its text
     */
    static Map<Path, String> render(UcdModel model) {
        Map<Path, String> sources = new LinkedHashMap<>();
        sources.put(Path.of(SOURCES + "case_tables.go"), caseTables(model));
        sources.put(Path.of(SOURCES + "normalization_tables.go"), normalizationTables(model));
        sources.put(Path.of(SOURCES + "white_space_tables.go"), whiteSpaceTables(model));
        sources.put(Path.of(SOURCES + "pattern_alphabet_tables.go"), patternAlphabetTables(model));
        // Each table is followed by a blank line, which gofmt takes off the end of a file.
        sources.replaceAll((path, text) -> text.stripTrailing() + "\n");
        return sources;
    }

    // case_tables.go

    private static String caseTables(UcdModel model) {
        UcdModel.Casing casing = model.casing();
        StringBuilder out = header(model, "The default case conversion's tables: the untailored full mapping and"
                        + " the properties Final_Sigma is stated over",
                List.of("UnicodeData.txt", "SpecialCasing.txt", "DerivedCoreProperties.txt"));
        mapping(out, "lowerMapping", "the full lowercase mapping; a code point not here maps to itself",
                casing.lower());
        mapping(out, "upperMapping", "the full uppercase mapping; a code point not here maps to itself",
                casing.upper());
        mapping(out, "finalSigmaMapping",
                "the lowercase mapping that replaces lowerMapping's at the end of a cased run (Final_Sigma)",
                casing.finalSigma());
        ranges(out, "casedRanges", "Cased", casing.cased());
        ranges(out, "caseIgnorableRanges", "Case_Ignorable", casing.caseIgnorable());
        return out.toString();
    }

    // normalization_tables.go

    private static String normalizationTables(UcdModel model) {
        UcdModel.Decomposition decomposition = model.decomposition();
        StringBuilder out = header(model,
                "Normalization's tables: the decompositions, the combining classes and the script-specific"
                        + " composition exclusions, checked against DerivedNormalizationProps.txt's"
                        + " Full_Composition_Exclusion when they were generated",
                List.of("UnicodeData.txt", "CompositionExclusions.txt", "DerivedNormalizationProps.txt"));
        mapping(out, "canonicalDecomposition",
                "the one-step canonical decomposition; a code point not here has none",
                decomposition.canonical());
        mapping(out, "compatibilityDecomposition",
                "the one-step compatibility decomposition, its <tag> dropped; a code point is in this,"
                        + " in canonicalDecomposition or in neither",
                decomposition.compatibility());

        SortedMap<Integer, Integer> ccc = decomposition.combiningClass().entries();
        out.append("// combiningClasses is the non-zero canonical combining classes, by code point; every\n");
        out.append("// other code point's is 0.\n");
        out.append("var combiningClasses = []combining{\n");
        ccc.forEach((cp, value) ->
                out.append("\t{").append(code(cp)).append(", ").append(value).append("},\n"));
        out.append("}\n\n");

        out.append("// scriptSpecificExclusions is CompositionExclusions.txt's script-specific exclusions, the\n");
        out.append("// composition eligibility UnicodeData.txt alone does not decide.\n");
        out.append("var scriptSpecificExclusions = []rune{\n");
        for (int cp : decomposition.scriptSpecificExclusions().members()) {
            out.append('\t').append(code(cp)).append(",\n");
        }
        out.append("}\n\n");

        out.append("// For each form, the least code point that is not a starter or whose quick check for the\n");
        out.append("// form is not Yes. Text made only of code points below it is its own normalization in\n");
        out.append("// that form.\n");
        out.append("const (\n");
        // gofmt lines the values up one column past the longest name.
        int widest = decomposition.trivialLimits().keySet().stream().mapToInt(String::length).max().orElse(0);
        decomposition.trivialLimits().forEach((form, limit) ->
                out.append('\t').append(form.toLowerCase(Locale.ROOT)).append("TrivialLimit")
                        .append(" ".repeat(widest - form.length())).append(" = ").append(code(limit))
                        .append('\n'));
        out.append(")\n");
        return out.toString();
    }

    // white_space_tables.go

    private static String whiteSpaceTables(UcdModel model) {
        StringBuilder out = header(model, "The White_Space set", List.of("PropList.txt"));
        ranges(out, "whiteSpaceRanges", "White_Space", model.whiteSpace());
        return out.toString();
    }

    // pattern_alphabet_tables.go

    private static String patternAlphabetTables(UcdModel model) {
        StringBuilder out = header(model,
                "The characters a pattern keeps a backslash before for an escape: the letters and the"
                        + " decimal digits",
                List.of("extracted/DerivedGeneralCategory.txt"));
        ranges(out, "lettersAndDigits", "General_Category Lu, Ll, Lt, Lm, Lo and Nd", model.patternEscapeAlphabet());
        return out.toString();
    }

    // What every file holds

    /** The comment that marks a file generated, what it holds and where it was generated from, and
     *  the package clause. {@code what} is a sentence without its full stop. */
    private static StringBuilder header(UcdModel model, String what, List<String> files) {
        StringBuilder out = new StringBuilder();
        out.append("// Code generated by gen/Generate.java from Unicode ").append(model.version())
                .append(". DO NOT EDIT.\n\n");
        comment(out, what + ", as of Unicode " + model.version() + ".");
        out.append("//\n");
        out.append("// Regenerate on a Unicode version bump with java gen/Generate.java ucd/<version>.\n");
        out.append("// SHA-256 of each input as downloaded (https://www.unicode.org/Public/")
                .append(model.version()).append("/ucd/):\n");
        out.append("//\n");
        for (String file : files) {
            out.append("//   - ").append(file).append(": ").append(model.sha256().get(file)).append('\n');
        }
        out.append('\n');
        out.append("package notation199x\n\n");
        return out;
    }

    private static void mapping(StringBuilder out, String name, String what, UcdModel.CodePointMapping mapping) {
        out.append("// ").append(name).append(" is ").append(what).append(" (")
                .append(mapping.entries().size()).append(" code points).\n");
        out.append("var ").append(name).append(" = mapping{\n");
        mapping.entries().forEach((cp, mapped) -> {
            out.append("\t{").append(code(cp)).append(", []rune{");
            for (int i = 0; i < mapped.length; i++) {
                out.append(i > 0 ? ", " : "").append(code(mapped[i]));
            }
            out.append("}},\n");
        });
        out.append("}\n\n");
    }

    private static void ranges(StringBuilder out, String name, String property, UcdModel.RangeSet ranges) {
        out.append("// ").append(name).append(" is ").append(property)
                .append(", as sorted inclusive ranges that do not overlap.\n");
        out.append("var ").append(name).append(" = rangeSet{\n");
        for (int[] r : ranges.ranges()) {
            out.append("\t{").append(code(r[0])).append(", ").append(code(r[1])).append("},\n");
        }
        out.append("}\n\n");
    }

    /** {@code text} as line comments, broken between words before the hundredth column. */
    private static void comment(StringBuilder out, String text) {
        StringBuilder line = new StringBuilder("//");
        for (String word : text.split(" ")) {
            if (line.length() + 1 + word.length() > 100) {
                out.append(line).append('\n');
                line = new StringBuilder("//");
            }
            line.append(' ').append(word);
        }
        out.append(line).append('\n');
    }

    /** A code point as a Go literal, at least four hex digits as the database writes them. */
    private static String code(int cp) {
        return String.format("0x%04X", cp);
    }
}
