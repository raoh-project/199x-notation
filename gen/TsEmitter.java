import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Writes a {@link UcdModel} as the TypeScript sources {@code ts/} reads.
 *
 * <p>What is decided here is how TypeScript holds the data: which file each table is in, the comment
 * it carries, and the constant each is written as. Each rule's tables are a module of their own that
 * does nothing but declare them, so a bundler leaves out the tables of a rule a program does not
 * call. A table of a value for each code point is written as two strings whose code units are the
 * values, {@code <NAME>_BLOCKS} and {@code <NAME>_PAGES}, read with {@code charCodeAt}: a string
 * literal is laid out by the engine as it parses it and decoded by nothing at run time, where an
 * array literal of numbers would be four times the source and a typed array would be built by code
 * that runs when the module loads. A mapping is written as the text each code point maps to. A code
 * point is written as a number and checked to be a scalar value here, since nothing in the language
 * holds a number to one. Nothing here is about Unicode.
 */
final class TsEmitter {

    private static final String SOURCES = "ts/src/";

    private TsEmitter() {}

    /**
     * Every source the model is written as, by where it goes.
     *
     * @param model the model
     * @return the path of each source, from the repository root, and its text
     */
    static Map<Path, String> render(UcdModel model) {
        Map<Path, String> sources = new LinkedHashMap<>();
        sources.put(Path.of(SOURCES + "case_tables.ts"), caseTables(model));
        sources.put(Path.of(SOURCES + "normalization_tables.ts"), normalizationTables(model));
        sources.put(Path.of(SOURCES + "white_space_tables.ts"), whiteSpaceTables(model));
        sources.put(Path.of(SOURCES + "pattern_alphabet_tables.ts"), patternAlphabetTables(model));
        // Each table is followed by a blank line, which is not wanted at the end of a file.
        sources.replaceAll((path, text) -> text.stripTrailing() + "\n");
        return sources;
    }

    // case_tables.ts

    private static String caseTables(UcdModel model) {
        UcdModel.Casing casing = model.casing();
        StringBuilder out = header(model, "The default case conversion's tables: the untailored full mapping and"
                        + " the properties Final_Sigma is stated over",
                List.of("UnicodeData.txt", "SpecialCasing.txt", "DerivedCoreProperties.txt"));
        mapping(out, "LOWER", "The full lowercase mapping; a code point not here maps to itself", casing.lower());
        mapping(out, "UPPER", "The full uppercase mapping; a code point not here maps to itself", casing.upper());
        mapping(out, "FINAL_SIGMA",
                "The lowercase mapping that replaces `LOWER`'s at the end of a cased run (`Final_Sigma`)",
                casing.finalSigma());
        paged(out, "CASE_CONTEXT", "For each code point, a bit each for what `Final_Sigma` asks of it: 1 where it"
                + " is `Cased`, 2 where it is `Case_Ignorable`, and 4 where `FINAL_SIGMA` names it.",
                model.byCodePoint().caseContext());
        paged(out, "LOWER_POSITION", "For each code point, 0 where `LOWER` maps it to itself, and otherwise one"
                + " more than where it is in `LOWER`. No code point `FINAL_SIGMA` names is 0.",
                model.byCodePoint().lower());
        paged(out, "UPPER_POSITION", "For each code point, 0 where `UPPER` maps it to itself, and otherwise one"
                + " more than where it is in `UPPER`.", model.byCodePoint().upper());
        return out.toString();
    }

    // normalization_tables.ts

    private static String normalizationTables(UcdModel model) {
        UcdModel.Decomposition decomposition = model.decomposition();
        StringBuilder out = header(model,
                "Normalization's tables: the decompositions, the combining classes and the script-specific"
                        + " composition exclusions, checked against DerivedNormalizationProps.txt's"
                        + " Full_Composition_Exclusion when they were generated",
                List.of("UnicodeData.txt", "CompositionExclusions.txt", "DerivedNormalizationProps.txt"));
        mapping(out, "CANONICAL", "The one-step canonical decomposition; a code point not here has none",
                decomposition.canonical());
        mapping(out, "COMPATIBILITY", "The one-step compatibility decomposition, its `<tag>` dropped; a code point"
                + " is in this, in `CANONICAL` or in neither", decomposition.compatibility());
        paged(out, "DECOMPOSITION_POSITION", "For each code point, 0 where it has no decomposition, one more than"
                + " where it is in `CANONICAL_TO` where it has a canonical one, and otherwise `CANONICAL_TO.length`"
                + " and one more than where it is in `COMPATIBILITY_TO`.", model.byCodePoint().decomposition());
        paged(out, "COMBINING_CLASS", "Each code point's canonical combining class, 0 for a starter.",
                model.byCodePoint().combiningClass());
        paged(out, "STABLE", "For each code point, the forms it is a stable starter in, a bit each: NFC 1, NFD 2,"
                + " NFKC 4 and NFKD 8. A stable starter is a starter whose quick check for the form is Yes. Text"
                + " made only of them is its own normalization in the form, and one of them ends what comes"
                + " before it: no mark after it is put in order before it or composes with a starter before it,"
                + " and it composes with nothing before it, since what does is Maybe, which the generator"
                + " checks.", model.byCodePoint().stableStarters());
        UcdModel.Composing composing = model.byCodePoint().composing();
        paged(out, "COMPOSITION_FIRST", "For each code point, 0 where it is the first member of no primary"
                + " composite, and otherwise one more than its row of `COMPOSITION_CELLS`.", composing.firsts());
        paged(out, "COMPOSITION_SECOND", "For each code point, 0 where it is the second member of no primary"
                + " composite, and otherwise one more than its column of `COMPOSITION_CELLS`. A starter before a"
                + " code point may compose with it by the table where and only where this is not 0.",
                composing.seconds());
        docComment(out, "How many second members there are, the length of a row of `COMPOSITION_CELLS`.");
        out.append("export const COMPOSITION_COLUMNS = ").append(composing.columns()).append(";\n\n");
        docComment(out, "At `row * COMPOSITION_COLUMNS + column`, the code unit 0 where the pair does not compose,"
                + " and otherwise one more than where the composite is in `COMPOSITES`. Hangul's, which are"
                + " arithmetic, are not here.");
        out.append("export const COMPOSITION_CELLS = ").append(units(composing.cells())).append(";\n\n");
        docComment(out, "The primary composites, in order of code point.");
        out.append("export const COMPOSITES: readonly number[] = [\n");
        codes(out, composing.composites());
        out.append("];\n\n");
        for (String form : UcdModel.FORMS) {
            docComment(out, "The least code point that is not a starter or whose quick check for " + form
                    + " is not Yes. Text made only of code points below it is its own normalization in that form.");
            out.append("export const ").append(form.toUpperCase(Locale.ROOT)).append("_TRIVIAL_LIMIT = ")
                    .append(code(decomposition.trivialLimit(form))).append(";\n\n");
        }
        docComment(out, "The most code points one code point decomposes into fully, in any form.");
        out.append("export const LONGEST_DECOMPOSITION = ")
                .append(model.normalizationDerived().longestDecomposition()).append(";\n\n");
        docComment(out, "The most marks one starter composes with, one after another, in a composing form: of the"
                + " marks held after a starter, no more than this many are gone from the answer.");
        out.append("export const MOST_MARKS_COMPOSED = ")
                .append(model.normalizationDerived().mostMarksComposed()).append(";\n\n");
        return out.toString();
    }

    // white_space_tables.ts

    private static String whiteSpaceTables(UcdModel model) {
        StringBuilder out = header(model, "The White_Space set", List.of("PropList.txt"));
        ranges(out, "WHITE_SPACE", "`White_Space`", model.whiteSpace());
        return out.toString();
    }

    // pattern_alphabet_tables.ts

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

    private static void ranges(StringBuilder out, String name, String property, UcdModel.RangeSet ranges) {
        docComment(out, property + ", as sorted inclusive ranges that do not overlap: the first and last code point"
                + " of each, one range after another.");
        out.append("export const ").append(name).append(": readonly number[] = [\n");
        for (int[] r : ranges.ranges()) {
            out.append("  ").append(code(r[0])).append(", ").append(code(r[1])).append(",\n");
        }
        out.append("];\n\n");
    }

    /**
     * {@code mapping} as two constants, {@code <NAME>_FROM}, the code points it maps in order, and
     * {@code <NAME>_TO}, the text each maps to, at the same place.
     */
    private static void mapping(StringBuilder out, String name, String what, UcdModel.CodePointMapping mapping) {
        docComment(out, what + " (" + mapping.entries().size() + " code points): the code points, in order.");
        out.append("export const ").append(name).append("_FROM: readonly number[] = [\n");
        codes(out, mapping.entries().keySet().stream().mapToInt(Integer::intValue).toArray());
        out.append("];\n\n");
        docComment(out, "What each of `" + name + "_FROM` maps to, at the same place.");
        out.append("export const ").append(name).append("_TO: readonly string[] = [\n");
        StringBuilder line = new StringBuilder("  ");
        for (int[] mapped : mapping.entries().values()) {
            for (int cp : mapped) {
                code(cp);
            }
            String each = text(mapped) + ",";
            if (line.length() + 1 + each.length() > 100) {
                out.append(line.toString().stripTrailing()).append('\n');
                line = new StringBuilder("  ");
            }
            line.append(each).append(' ');
        }
        out.append(line.toString().stripTrailing()).append("\n];\n\n");
    }

    /**
     * {@code table} as two string constants, {@code <NAME>_BLOCKS} and {@code <NAME>_PAGES}, whose code
     * units are its values: the value of {@code cp} is
     * {@code <NAME>_PAGES.charCodeAt(<NAME>_BLOCKS.charCodeAt(cp >> 8) << 8 | cp & 0xFF)}.
     */
    private static void paged(StringBuilder out, String name, String what, PagedTable table) {
        if (table.pages().size() > 0x10000 / PagedTable.PAGE || table.greatest() > 0xFFFF) {
            throw new IllegalStateException(name + " does not fit the strings it is written as");
        }
        docComment(out, what + " The value of `cp` is `" + name + "_PAGES.charCodeAt(" + name + "_BLOCKS.charCodeAt(cp"
                + " >> " + PagedTable.SHIFT + ") << " + PagedTable.SHIFT + " | cp & 0x" + UcdModel.hex(PagedTable.PAGE - 1)
                + ")`: each block of " + PagedTable.PAGE + " code points has the page of its values, and blocks that"
                + " hold the same values share one (" + table.pages().size() + " pages).");
        out.append("export const ").append(name).append("_BLOCKS = ").append(units(table.blocks())).append(";\n\n");
        out.append("export const ").append(name).append("_PAGES = ").append(units(table.values())).append(";\n\n");
    }

    /** {@code values}, each from 0 to 0xFFFF, as a string literal whose code units they are, broken
     *  over lines of a hundred columns at most. */
    private static String units(int[] values) {
        StringBuilder out = new StringBuilder("\"\" +\n");
        StringBuilder line = new StringBuilder("  \"");
        for (int i = 0; i < values.length; i++) {
            int value = values[i];
            if (value < 0 || value > 0xFFFF) {
                throw new IllegalStateException(value + " is no code unit");
            }
            boolean digitNext = i + 1 < values.length && values[i + 1] >= '0' && values[i + 1] <= '9';
            String unit = unit(value, digitNext);
            if (line.length() + unit.length() + 4 > 100) {
                out.append(line).append("\" +\n");
                line = new StringBuilder("  \"");
            }
            line.append(unit);
        }
        return out.append(line).append('"').toString();
    }

    /** One code unit as it is written in a string literal: printable ASCII as itself, NUL as
     *  {@code \\0} where no digit follows it, and every other unit by its hex digits. */
    private static String unit(int value, boolean digitNext) {
        if (value == 0 && !digitNext) {
            return "\\0";
        }
        if (value >= 0x20 && value < 0x7F && value != '"' && value != '\\') {
            return String.valueOf((char) value);
        }
        return value < 0x100 ? String.format("\\x%02X", value) : String.format("\\u%04X", value);
    }

    /** {@code mapped}, scalar values, as a string literal. */
    private static String text(int[] mapped) {
        StringBuilder out = new StringBuilder("\"");
        for (int cp : mapped) {
            if (cp >= 0x20 && cp < 0x7F && cp != '"' && cp != '\\') {
                out.append((char) cp);
            } else if (cp <= 0xFFFF) {
                out.append(String.format("\\u%04X", cp));
            } else {
                out.append(String.format("\\u{%X}", cp));
            }
        }
        return out.append('"').toString();
    }

    /** {@code cps} as hex literals, eight to a line. */
    private static void codes(StringBuilder out, int[] cps) {
        for (int i = 0; i < cps.length; i += 8) {
            out.append(" ");
            for (int j = i; j < Math.min(i + 8, cps.length); j++) {
                out.append(' ').append(code(cps[j])).append(',');
            }
            out.append('\n');
        }
    }

    /** {@code text} as a doc comment, broken between words before the hundredth column. */
    private static void docComment(StringBuilder out, String text) {
        out.append("/**\n");
        StringBuilder line = new StringBuilder(" *");
        for (String word : text.split(" ")) {
            if (line.length() + 1 + word.length() > 100) {
                out.append(line).append('\n');
                line = new StringBuilder(" *");
            }
            line.append(' ').append(word);
        }
        out.append(line).append("\n */\n");
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

    /** A code point as a hex literal of at least four digits, as the database writes them. A
     *  surrogate is no scalar value, and every code point a table holds is one. */
    private static String code(int cp) {
        if (cp >= 0xD800 && cp <= 0xDFFF || cp < 0 || cp > 0x10FFFF) {
            throw new IllegalStateException(UcdModel.hex(cp) + " is no scalar value");
        }
        return String.format("0x%04X", cp);
    }
}
