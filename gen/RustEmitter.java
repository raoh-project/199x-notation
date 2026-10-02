import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Writes a {@link UcdModel} as the Rust sources {@code rust/} reads.
 *
 * <p>What is decided here is how Rust holds the data: which file each table is in, the comment it
 * carries, and the static each is written as. A static slice of constants is laid out by the
 * compiler and decoded by nothing at run time, so every table is written as the values it holds.
 * A code point is written as a {@code char}, which holds a scalar value and nothing else, so a
 * surrogate in a table stops the generator here rather than the compiler later. The crate declares
 * these files with rustfmt told to skip them, so nothing here has to be laid out as rustfmt would.
 * Nothing here is about Unicode.
 */
final class RustEmitter {

    private static final String SOURCES = "rust/src/";

    private RustEmitter() {}

    /**
     * Every source the model is written as, by where it goes.
     *
     * @param model the model
     * @return the path of each source, from the repository root, and its text
     */
    static Map<Path, String> render(UcdModel model) {
        Map<Path, String> sources = new LinkedHashMap<>();
        sources.put(Path.of(SOURCES + "case_tables.rs"), caseTables(model));
        sources.put(Path.of(SOURCES + "normalization_tables.rs"), normalizationTables(model));
        sources.put(Path.of(SOURCES + "white_space_tables.rs"), whiteSpaceTables(model));
        sources.put(Path.of(SOURCES + "pattern_alphabet_tables.rs"), patternAlphabetTables(model));
        // Each table is followed by a blank line, which is not wanted at the end of a file.
        sources.replaceAll((path, text) -> text.stripTrailing() + "\n");
        return sources;
    }

    // case_tables.rs

    private static String caseTables(UcdModel model) {
        UcdModel.Casing casing = model.casing();
        StringBuilder out = header(model, "The default case conversion's tables: the untailored full mapping and"
                        + " the properties Final_Sigma is stated over",
                List.of("UnicodeData.txt", "SpecialCasing.txt", "DerivedCoreProperties.txt"));
        mapping(out, "LOWER", "The full lowercase mapping; a code point not here maps to itself",
                casing.lower());
        mapping(out, "UPPER", "The full uppercase mapping; a code point not here maps to itself",
                casing.upper());
        mapping(out, "FINAL_SIGMA",
                "The lowercase mapping that replaces `LOWER`'s at the end of a cased run (`Final_Sigma`)",
                casing.finalSigma());
        ranges(out, "CASED", "`Cased`", casing.cased());
        ranges(out, "CASE_IGNORABLE", "`Case_Ignorable`", casing.caseIgnorable());
        return out.toString();
    }

    // normalization_tables.rs

    private static String normalizationTables(UcdModel model) {
        UcdModel.Decomposition decomposition = model.decomposition();
        StringBuilder out = header(model,
                "Normalization's tables: the decompositions, the combining classes and the script-specific"
                        + " composition exclusions, checked against DerivedNormalizationProps.txt's"
                        + " Full_Composition_Exclusion when they were generated",
                List.of("UnicodeData.txt", "CompositionExclusions.txt", "DerivedNormalizationProps.txt"));
        mapping(out, "CANONICAL", "The one-step canonical decomposition; a code point not here has none",
                decomposition.canonical());
        mapping(out, "COMPATIBILITY",
                "The one-step compatibility decomposition, its `<tag>` dropped; a code point is in this,"
                        + " in `CANONICAL` or in neither",
                decomposition.compatibility());

        out.append("/// The non-zero canonical combining classes, by code point; every other code point's is 0.\n");
        out.append("pub(crate) static COMBINING_CLASSES: &[(char, u8)] = &[\n");
        decomposition.combiningClass().entries().forEach((cp, value) ->
                out.append("    (").append(code(cp)).append(", ").append(value).append("),\n"));
        out.append("];\n\n");

        out.append("/// `CompositionExclusions.txt`'s script-specific exclusions, the composition eligibility\n");
        out.append("/// `UnicodeData.txt` alone does not decide.\n");
        out.append("pub(crate) static SCRIPT_SPECIFIC_EXCLUSIONS: &[char] = &[\n");
        for (int cp : decomposition.scriptSpecificExclusions().members()) {
            out.append("    ").append(code(cp)).append(",\n");
        }
        out.append("];\n\n");

        decomposition.trivialLimits().forEach((form, limit) -> {
            out.append("/// The least code point that is not a starter or whose quick check for ").append(form)
                    .append(" is not Yes.\n");
            out.append("/// Text made only of code points below it is its own normalization in that form.\n");
            out.append("pub(crate) const ").append(form.toUpperCase(Locale.ROOT)).append("_TRIVIAL_LIMIT: char = ")
                    .append(code(limit)).append(";\n\n");
        });
        return out.toString();
    }

    // white_space_tables.rs

    private static String whiteSpaceTables(UcdModel model) {
        StringBuilder out = header(model, "The White_Space set", List.of("PropList.txt"));
        ranges(out, "WHITE_SPACE", "`White_Space`", model.whiteSpace());
        return out.toString();
    }

    // pattern_alphabet_tables.rs

    private static String patternAlphabetTables(UcdModel model) {
        StringBuilder out = header(model,
                "The characters a pattern keeps a backslash before for an escape: the letters and the"
                        + " decimal digits",
                List.of("extracted/DerivedGeneralCategory.txt"));
        ranges(out, "LETTERS_AND_DIGITS", "General_Category `Lu`, `Ll`, `Lt`, `Lm`, `Lo` and `Nd`",
                model.patternEscapeAlphabet());
        return out.toString();
    }

    // What every file holds

    /** The comment that marks a file generated, what it holds and where it was generated from.
     *  {@code what} is a sentence without its full stop. */
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
        return out;
    }

    private static void mapping(StringBuilder out, String name, String what, UcdModel.CodePointMapping mapping) {
        out.append("/// ").append(what).append(" (").append(mapping.entries().size()).append(" code points).\n");
        out.append("pub(crate) static ").append(name).append(": &[(char, &[char])] = &[\n");
        mapping.entries().forEach((cp, mapped) -> {
            out.append("    (").append(code(cp)).append(", &[");
            for (int i = 0; i < mapped.length; i++) {
                out.append(i > 0 ? ", " : "").append(code(mapped[i]));
            }
            out.append("]),\n");
        });
        out.append("];\n\n");
    }

    private static void ranges(StringBuilder out, String name, String property, UcdModel.RangeSet ranges) {
        out.append("/// ").append(property).append(", as sorted inclusive ranges that do not overlap.\n");
        out.append("pub(crate) static ").append(name).append(": &[(char, char)] = &[\n");
        for (int[] r : ranges.ranges()) {
            out.append("    (").append(code(r[0])).append(", ").append(code(r[1])).append("),\n");
        }
        out.append("];\n\n");
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

    /** A code point as a Rust {@code char} literal, at least four hex digits as the database writes
     *  them. A surrogate is no {@code char}. */
    private static String code(int cp) {
        if (cp >= 0xD800 && cp <= 0xDFFF || cp < 0 || cp > 0x10FFFF) {
            throw new IllegalStateException(UcdModel.hex(cp) + " is no scalar value, and a char holds one");
        }
        return String.format("'\\u{%04X}'", cp);
    }
}
