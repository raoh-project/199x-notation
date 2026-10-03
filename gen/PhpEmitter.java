import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Writes a {@link UcdModel} as the PHP sources {@code php/} reads.
 *
 * <p>What is decided here is how PHP holds the data: which class each table is a constant of, the
 * comment it carries, and the literal each is written as. A constant array of literals is built by
 * the compiler, and opcache keeps it immutable and shared, so every table is written as the values
 * it holds. A PHP string is bytes, and the implementation reads text a character at a time as the
 * bytes of that character, so a mapping is keyed by a character as its UTF-8 bytes and maps to the
 * UTF-8 bytes it maps to: a lookup takes the bytes it was handed and gives bytes to write, with no
 * array inside an array. A character is written as a PHP Unicode escape, {@code "&#92;u{...}"}, which PHP encodes
 * as UTF-8 and which holds a scalar value and nothing else, so a surrogate in a table stops the
 * generator here. A set of ranges is a flat list of ints, two to a range, for a search. Nothing
 * here is about Unicode.
 */
final class PhpEmitter {

    private static final String SOURCES = "php/src/Internal/";

    private PhpEmitter() {}

    /**
     * Every source the model is written as, by where it goes.
     *
     * @param model the model
     * @return the path of each source, from the repository root, and its text
     */
    static Map<Path, String> render(UcdModel model) {
        Map<Path, String> sources = new LinkedHashMap<>();
        sources.put(Path.of(SOURCES + "CaseTables.php"), caseTables(model));
        sources.put(Path.of(SOURCES + "NormalizationTables.php"), normalizationTables(model));
        sources.put(Path.of(SOURCES + "WhiteSpaceTables.php"), whiteSpaceTables(model));
        sources.put(Path.of(SOURCES + "PatternAlphabetTables.php"), patternAlphabetTables(model));
        return sources;
    }

    // CaseTables.php

    private static String caseTables(UcdModel model) {
        UcdModel.Casing casing = model.casing();
        StringBuilder out = header(model, "CaseTables", "The default case conversion's tables: the untailored"
                        + " full mapping and the properties Final_Sigma is stated over",
                List.of("UnicodeData.txt", "SpecialCasing.txt", "DerivedCoreProperties.txt"));
        mapping(out, "LOWER", "The full lowercase mapping; a character not here maps to itself", casing.lower());
        mapping(out, "UPPER", "The full uppercase mapping; a character not here maps to itself", casing.upper());
        mapping(out, "FINAL_SIGMA",
                "The lowercase mapping that replaces LOWER's at the end of a cased run (Final_Sigma)",
                casing.finalSigma());
        ranges(out, "CASED", "Cased", casing.cased());
        ranges(out, "CASE_IGNORABLE", "Case_Ignorable", casing.caseIgnorable());
        return footer(out);
    }

    // NormalizationTables.php

    private static String normalizationTables(UcdModel model) {
        UcdModel.Decomposition decomposition = model.decomposition();
        StringBuilder out = header(model, "NormalizationTables",
                "Normalization's tables: the decompositions, the combining classes and the primary"
                        + " composites, whose exclusions were checked against DerivedNormalizationProps.txt's"
                        + " Full_Composition_Exclusion when they were generated",
                List.of("UnicodeData.txt", "CompositionExclusions.txt", "DerivedNormalizationProps.txt"));
        mapping(out, "CANONICAL", "The one-step canonical decomposition; a character not here has none",
                decomposition.canonical());
        mapping(out, "COMPATIBILITY",
                "The one-step compatibility decomposition, its <tag> dropped; a character is in this, in"
                        + " CANONICAL or in neither",
                decomposition.compatibility());

        out.append("    /**\n");
        out.append("     * The non-zero canonical combining classes, by character; every other character's is 0.\n");
        out.append("     *\n");
        out.append("     * @var array<string, int>\n");
        out.append("     */\n");
        out.append("    public const COMBINING_CLASSES = [\n");
        decomposition.combiningClass().entries().forEach((cp, value) ->
                out.append("        ").append(character(cp)).append(" => ").append(value).append(",\n"));
        out.append("    ];\n\n");

        // Held as generated rather than worked out as text is normalized: PHP keeps nothing it works
        // out from one request to the next, and opcache keeps a constant.
        UcdModel.CodePointMapping compositions = model.normalizationDerived().compositions();
        out.append("    /**\n");
        out.append("     * The primary composites, by the two characters each is canonically composed from, written\n");
        out.append("     * one after the other; Hangul's, which are arithmetic, are not here (")
                .append(compositions.entries().size()).append(" composites).\n");
        out.append("     *\n");
        out.append("     * @var array<string, string>\n");
        out.append("     */\n");
        out.append("    public const COMPOSITIONS = [\n");
        compositions.entries().forEach((cp, pair) -> out.append("        \"").append(escape(pair[0]))
                .append(escape(pair[1])).append("\" => ").append(character(cp)).append(",\n"));
        out.append("    ];\n\n");

        PagedTable stable = model.byCodePoint().stableStarters();
        if (stable.pages().size() > 0x100 || stable.greatest() > 0xFF) {
            throw new IllegalStateException("STABLE does not fit the strings it is written as");
        }
        out.append("    /**\n");
        comment(out, "    ", "For each code point, the forms it is a stable starter in, a bit each: NFC 1, NFD 2, NFKC 4 and"
                + " NFKD 8, as the byte at ord(STABLE_BLOCKS[$cp >> " + PagedTable.SHIFT + "]) << " + PagedTable.SHIFT
                + " | $cp & 0x" + UcdModel.hex(PagedTable.PAGE - 1) + " of STABLE_PAGES: each block of "
                + PagedTable.PAGE + " code points has the page of its values, and blocks that hold the same values"
                + " share one (" + stable.pages().size() + " pages). A stable starter is a starter whose quick"
                + " check for the form is Yes. Text made only of them is its own normalization in the form, and"
                + " one of them ends what comes before it: no mark after it is put in order before it or composes"
                + " with a starter before it, and it composes with nothing before it, since what does is Maybe,"
                + " which the generator checks.");
        out.append("     */\n");
        out.append("    public const STABLE_BLOCKS = ").append(bytes(stable.blocks())).append(";\n\n");
        out.append("    /** The pages STABLE_BLOCKS gives each block. */\n");
        out.append("    public const STABLE_PAGES = ").append(bytes(stable.values())).append(";\n\n");

        for (String form : UcdModel.FORMS) {
            int limit = decomposition.trivialLimit(form);
            out.append("    /**\n");
            out.append("     * The least code point that is not a starter or whose quick check for ").append(form)
                    .append(" is not Yes.\n");
            out.append("     * Text made only of code points below it is its own normalization in that form.\n");
            out.append("     */\n");
            out.append("    public const ").append(form.toUpperCase(Locale.ROOT)).append("_TRIVIAL_LIMIT = ")
                    .append(code(limit)).append(";\n\n");
        }
        return footer(out);
    }

    // WhiteSpaceTables.php

    private static String whiteSpaceTables(UcdModel model) {
        StringBuilder out = header(model, "WhiteSpaceTables", "The White_Space set", List.of("PropList.txt"));
        ranges(out, "WHITE_SPACE", "White_Space", model.whiteSpace());
        return footer(out);
    }

    // PatternAlphabetTables.php

    private static String patternAlphabetTables(UcdModel model) {
        StringBuilder out = header(model, "PatternAlphabetTables",
                "The characters a pattern keeps a backslash before for an escape: the letters and the"
                        + " decimal digits",
                List.of("extracted/DerivedGeneralCategory.txt"));
        ranges(out, "LETTERS_AND_DIGITS", "General_Category Lu, Ll, Lt, Lm, Lo and Nd",
                model.patternEscapeAlphabet());
        return footer(out);
    }

    // What every file holds

    /** The opening tag, the comment that marks a file generated, what it holds and where it was
     *  generated from, and the class the tables are constants of. {@code what} is a sentence without
     *  its full stop. */
    private static StringBuilder header(UcdModel model, String className, String what, List<String> files) {
        StringBuilder out = new StringBuilder();
        out.append("<?php\n\n");
        out.append("// Code generated by gen/Generate.java from Unicode ").append(model.version())
                .append(". DO NOT EDIT.\n\n");
        out.append("declare(strict_types=1);\n\n");
        out.append("namespace Raoh\\Notation199x\\Internal;\n\n");
        out.append("/**\n");
        comment(out, what + ", as of Unicode " + model.version() + ".");
        out.append(" *\n");
        out.append(" * Regenerate on a Unicode version bump with java gen/Generate.java ucd/<version>.\n");
        out.append(" * SHA-256 of each input as downloaded (https://www.unicode.org/Public/")
                .append(model.version()).append("/ucd/):\n");
        out.append(" *\n");
        for (String file : files) {
            out.append(" *   - ").append(file).append(": ").append(model.sha256().get(file)).append('\n');
        }
        out.append(" *\n");
        out.append(" * @internal\n");
        out.append(" */\n");
        out.append("final class ").append(className).append("\n{\n");
        return out;
    }

    /** The class's closing brace after its last constant, whose blank line it takes. */
    private static String footer(StringBuilder out) {
        return out.toString().stripTrailing() + "\n}\n";
    }

    private static void mapping(StringBuilder out, String name, String what, UcdModel.CodePointMapping mapping) {
        out.append("    /**\n");
        out.append("     * ").append(what).append(" (").append(mapping.entries().size()).append(" code points).\n");
        out.append("     *\n");
        out.append("     * @var array<string, string>\n");
        out.append("     */\n");
        out.append("    public const ").append(name).append(" = [\n");
        mapping.entries().forEach((cp, mapped) -> {
            out.append("        ").append(character(cp)).append(" => \"");
            for (int each : mapped) {
                out.append(escape(each));
            }
            out.append("\",\n");
        });
        out.append("    ];\n\n");
    }

    private static void ranges(StringBuilder out, String name, String property, UcdModel.RangeSet ranges) {
        out.append("    /**\n");
        out.append("     * ").append(property)
                .append(", as sorted inclusive ranges that do not overlap, each its first and its last\n");
        out.append("     * code point.\n");
        out.append("     *\n");
        out.append("     * @var list<int>\n");
        out.append("     */\n");
        out.append("    public const ").append(name).append(" = [\n");
        for (int[] r : ranges.ranges()) {
            out.append("        ").append(code(r[0])).append(", ").append(code(r[1])).append(",\n");
        }
        out.append("    ];\n\n");
    }

    /** {@code values}, each a byte, as one PHP string literal of them. One literal rather than lines
     *  joined with the concatenation operator, which PHPStan works out at a cost that grows past its
     *  default memory limit. */
    private static String bytes(int[] values) {
        StringBuilder out = new StringBuilder("\"");
        for (int value : values) {
            out.append(String.format("\\x%02X", value));
        }
        return out.append('"').toString();
    }

    /** {@code text} as the lines of a doc comment, broken between words before the hundredth
     *  column. */
    private static void comment(StringBuilder out, String text) {
        comment(out, "", text);
    }

    /** {@link #comment(StringBuilder, String)}, each line after {@code indent}. */
    private static void comment(StringBuilder out, String indent, String text) {
        StringBuilder line = new StringBuilder(indent + " *");
        for (String word : text.split(" ")) {
            if (line.length() + 1 + word.length() > 100) {
                out.append(line).append('\n');
                line = new StringBuilder(indent + " *");
            }
            line.append(' ').append(word);
        }
        out.append(line).append('\n');
    }

    /** A character as a PHP string literal of its UTF-8 bytes. */
    private static String character(int cp) {
        return "\"" + escape(cp) + "\"";
    }

    /** A code point as a PHP Unicode escape, {@code &#92;u{...}}, at least four hex digits as the database writes
     *  them. A surrogate is no scalar value, and has no UTF-8. */
    private static String escape(int cp) {
        if (cp >= 0xD800 && cp <= 0xDFFF || cp < 0 || cp > 0x10FFFF) {
            throw new IllegalStateException(UcdModel.hex(cp) + " is no scalar value, and has no UTF-8");
        }
        return String.format("\\u{%04X}", cp);
    }

    /** A code point as a PHP int literal, at least four hex digits. */
    private static String code(int cp) {
        return String.format("0x%04X", cp);
    }
}
