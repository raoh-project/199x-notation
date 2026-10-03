import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

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
        paged(out, "lowerPosition", "for each code point, 0 where lowerMapping maps it to itself, and otherwise one"
                + " more than where it is in lowerMapping. No code point finalSigmaMapping names is 0.",
                model.byCodePoint().lower(), "uint16");
        paged(out, "upperPosition", "for each code point, 0 where upperMapping maps it to itself, and otherwise one"
                + " more than where it is in upperMapping.", model.byCodePoint().upper(), "uint16");
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

        paged(out, "decompositionPosition", "for each code point, 0 where it has no decomposition, one more"
                + " than where it is in canonicalDecomposition where it has a canonical one, and otherwise"
                + " len(canonicalDecomposition) and one more than where it is in compatibilityDecomposition.",
                model.byCodePoint().decomposition(), "uint16");
        paged(out, "combiningClass", "each code point's canonical combining class, 0 for a starter.",
                model.byCodePoint().combiningClass(), "uint8");
        paged(out, "stable", "for each code point, the forms it is a stable starter in, a bit each: NFC 1, NFD 2,"
                + " NFKC 4 and NFKD 8. A stable starter is a starter whose quick check for the form is Yes. Text"
                + " made only of them is its own normalization in the form, and one of them ends what comes"
                + " before it: no mark after it is put in order before it or composes with a starter before it,"
                + " and it composes with nothing before it, since what does is Maybe, which the generator"
                + " checks.", model.byCodePoint().stableStarters(), "uint8");

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
        int widest = UcdModel.FORMS.stream().mapToInt(String::length).max().orElse(0);
        for (String form : UcdModel.FORMS) {
            out.append('\t').append(form.toLowerCase(Locale.ROOT)).append("TrivialLimit")
                    .append(" ".repeat(widest - form.length())).append(" = ")
                    .append(code(decomposition.trivialLimit(form))).append('\n');
        }
        out.append(")\n\n");

        out.append("// longestDecomposition is the most code points one code point decomposes into fully, in any\n");
        out.append("// form: the room a decomposition is written into.\n");
        out.append("const longestDecomposition = ").append(model.normalizationDerived().longestDecomposition())
                .append('\n');
        out.append('\n');
        out.append("// mostMarksComposed is the most marks one starter composes with, one after another, in a\n");
        out.append("// composing form: of the marks held after a starter, no more than this many are gone from the\n");
        out.append("// answer.\n");
        out.append("const mostMarksComposed = ").append(model.normalizationDerived().mostMarksComposed())
                .append('\n');
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

    /**
     * {@code table} as two arrays, {@code <name>Blocks} and {@code <name>Pages}, the pages' values
     * of type {@code type}: the value of {@code r} is at
     * {@code <name>Pages[int(<name>Blocks[r>>8])<<8|int(r&0xFF)]}.
     */
    private static void paged(StringBuilder out, String name, String what, PagedTable table, String type) {
        long most = type.equals("uint8") ? 0xFF : 0xFFFF;
        if (table.pages().size() > 0x100 || table.greatest() > most) {
            throw new IllegalStateException(name + " does not fit the arrays it is written as");
        }
        comment(out, name + "Blocks and " + name + "Pages are " + what + " The value of r is at "
                + name + "Pages[int(" + name + "Blocks[r>>" + PagedTable.SHIFT + "])<<" + PagedTable.SHIFT
                + "|int(r&0x" + UcdModel.hex(PagedTable.PAGE - 1) + ")]: each block of " + PagedTable.PAGE
                + " code points has the page of its values, and blocks that hold the same values share one ("
                + table.pages().size() + " pages).");
        out.append("var ").append(name).append("Blocks = [").append(table.blocks().length).append("]uint8{\n");
        values(out, table.blocks(), 2);
        out.append("}\n\n");
        int[] values = table.values();
        out.append("var ").append(name).append("Pages = [").append(values.length).append(']').append(type)
                .append("{\n");
        values(out, values, type.equals("uint8") ? 2 : 4);
        out.append("}\n\n");
    }

    /** {@code values} as hex literals of {@code digits} digits, sixteen to a line. */
    private static void values(StringBuilder out, int[] values, int digits) {
        for (int i = 0; i < values.length; i += 16) {
            out.append('\t');
            for (int j = i; j < Math.min(i + 16, values.length); j++) {
                out.append(j > i ? " " : "").append(String.format("0x%0" + digits + "X,", values[j]));
            }
            out.append('\n');
        }
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
