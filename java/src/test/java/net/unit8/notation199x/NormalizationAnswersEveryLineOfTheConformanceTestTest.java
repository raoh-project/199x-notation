package net.unit8.notation199x;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import net.unit8.notation199x.Normalization.Form;

/**
 * The four forms against Unicode's own {@code NormalizationTest.txt}, in full.
 *
 * <p>Each data line is five columns — source, NFC, NFD, NFKC, NFKD — of space-separated hex code
 * points. The file states what conformance is: {@code c2 == toNFC(c1) == toNFC(c2) == toNFC(c3)} and
 * {@code c4 == toNFC(c4) == toNFC(c5)}; {@code c3 == toNFD(c1) == toNFD(c2) == toNFD(c3)} and
 * {@code c5 == toNFD(c4) == toNFD(c5)}; {@code c4 == toNFKC} and {@code c5 == toNFKD} of all five.
 * And a code point no line of Part 1 names is its own normalization in every form.
 */
class NormalizationAnswersEveryLineOfTheConformanceTestTest {

    @Test
    void everyColumnNormalizesToTheColumnTheFileSaysItDoes() throws IOException {
        List<String> lines = Files.readAllLines(Ucd.file("NormalizationTest.txt"), StandardCharsets.UTF_8);
        assertEquals("# NormalizationTest-" + Ucd.VERSION + ".txt", lines.get(0));
        List<String> failed = new ArrayList<>();
        boolean[] named = new boolean[Character.MAX_CODE_POINT + 1];
        boolean partOne = false;
        int checked = 0;
        for (int lineNo = 1; lineNo <= lines.size(); lineNo++) {
            String data = lines.get(lineNo - 1).replaceFirst("#.*", "").trim();
            if (data.startsWith("@")) {
                partOne = data.equals("@Part1");
                continue;
            }
            if (data.isEmpty()) {
                continue;
            }
            String[] columns = data.split(";", -1);
            String[] c = new String[6];
            for (int i = 1; i <= 5; i++) {
                c[i] = decode(columns[i - 1]);
            }
            if (partOne) {
                named[c[1].codePointAt(0)] = true;
            }
            checked++;
            for (int i = 1; i <= 5; i++) {
                check(failed, lineNo, Form.NFC, i, c[i], c[i <= 3 ? 2 : 4]);
                check(failed, lineNo, Form.NFD, i, c[i], c[i <= 3 ? 3 : 5]);
                check(failed, lineNo, Form.NFKC, i, c[i], c[4]);
                check(failed, lineNo, Form.NFKD, i, c[i], c[5]);
            }
        }
        assertTrue(checked > 10_000, "the file's data lines were read: " + checked);
        for (int cp = 0; cp <= Character.MAX_CODE_POINT; cp++) {
            if (named[cp] || (cp >= Character.MIN_SURROGATE && cp <= Character.MAX_SURROGATE)) {
                continue;
            }
            String alone = Character.toString(cp);
            for (Form form : Form.values()) {
                if (!alone.equals(Normalization.normalize(form, alone))) {
                    failed.add(form + " changes U+" + Integer.toHexString(cp) + ", which Part 1 does not name");
                }
            }
        }
        assertEquals(List.of(), failed.subList(0, Math.min(failed.size(), 20)), failed.size() + " failed");
    }

    private static void check(List<String> failed, int lineNo, Form form, int column, String input,
            String expected) {
        String actual = Normalization.normalize(form, input);
        if (!actual.equals(expected)) {
            failed.add("line " + lineNo + ": " + form + "(c" + column + " " + hex(input) + ") = " + hex(actual)
                    + ", expected " + hex(expected));
        }
    }

    private static String decode(String hexList) {
        StringBuilder out = new StringBuilder();
        for (String token : hexList.trim().split("\\s+")) {
            out.appendCodePoint(Integer.parseInt(token, 16));
        }
        return out.toString();
    }

    private static String hex(String s) {
        StringBuilder sb = new StringBuilder();
        s.codePoints().forEach(cp -> sb.append(Integer.toHexString(cp)).append(' '));
        return sb.toString().trim();
    }
}
