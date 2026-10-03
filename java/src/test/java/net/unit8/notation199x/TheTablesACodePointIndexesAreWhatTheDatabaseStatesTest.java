package net.unit8.notation199x;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The tables a code point indexes, which the conversions and normalization ask as they read text,
 * say of every code point what the database says, or what the searched tables they stand in front
 * of say. They are generated from the same model as those, and held here to the database by a
 * reading of it of their own, rather than taken on the generator's word.
 */
class TheTablesACodePointIndexesAreWhatTheDatabaseStatesTest {

    /** A stable starter of a form is a code point whose combining class is 0 and whose quick check
     *  for the form is Yes, as {@code UnicodeData.txt} and {@code DerivedNormalizationProps.txt}
     *  state them. */
    @Test
    void aStableStarterIsAStarterWhoseQuickCheckIsYes() throws IOException {
        Map<Integer, Integer> classes = combiningClasses();
        Map<String, Set<Integer>> notYes = new HashMap<>();
        for (String line : Files.readAllLines(Ucd.file("DerivedNormalizationProps.txt"))) {
            String[] f = line.replaceFirst("#.*", "").split(";");
            if (f.length != 3 || !f[1].trim().endsWith("_QC") || f[2].trim().equals("Y")) {
                continue;
            }
            String range = f[0].trim();
            int dots = range.indexOf("..");
            int from = Integer.parseInt(dots < 0 ? range : range.substring(0, dots), 16);
            int to = dots < 0 ? from : Integer.parseInt(range.substring(dots + 2), 16);
            Set<Integer> set = notYes.computeIfAbsent(f[1].trim().replace("_QC", ""), form -> new HashSet<>());
            for (int cp = from; cp <= to; cp++) {
                set.add(cp);
            }
        }
        assertEquals(Set.of("NFC", "NFD", "NFKC", "NFKD"), notYes.keySet());
        List<String> wrong = new ArrayList<>();
        for (Normalization.Form form : Normalization.Form.values()) {
            Set<Integer> notYesOfForm = notYes.get(form.name());
            for (int cp = 0; cp <= Character.MAX_CODE_POINT; cp++) {
                boolean stated = !classes.containsKey(cp) && !notYesOfForm.contains(cp);
                if (Normalization.isStableStarter(form, cp) != stated) {
                    wrong.add(form + " " + Integer.toHexString(cp));
                }
            }
        }
        assertEquals(List.of(), wrong);
    }

    @Test
    void theCombiningClassIsTheOneTheDatabaseStates() throws IOException {
        Map<Integer, Integer> classes = combiningClasses();
        List<String> wrong = new ArrayList<>();
        for (int cp = 0; cp <= Character.MAX_CODE_POINT; cp++) {
            if (Normalization.combiningClass(cp) != classes.getOrDefault(cp, 0)) {
                wrong.add(Integer.toHexString(cp));
            }
        }
        assertEquals(List.of(), wrong);
    }

    /** Each case table answers 0 where the searched mapping has nothing for a code point or maps it
     *  to itself, and otherwise where the searched mapping holds it. */
    @Test
    void aCaseTableAnswersWhereTheMappingHoldsWhatChanges() {
        List<String> wrong = new ArrayList<>();
        for (boolean lower : new boolean[] {true, false}) {
            CaseTables.Mapping mapping = lower ? CaseTables.LOWER : CaseTables.UPPER;
            byte[] blocks = lower ? CaseTables.LOWER_BLOCKS : CaseTables.UPPER_BLOCKS;
            char[] pages = lower ? CaseTables.LOWER_PAGES : CaseTables.UPPER_PAGES;
            for (int cp = 0; cp <= Character.MAX_CODE_POINT; cp++) {
                int index = Arrays.binarySearch(mapping.codePoints(), cp);
                boolean changes = index >= 0 && !Arrays.equals(mapping.mapped()[index], new int[] {cp});
                int position = pages[(blocks[cp >>> 8] & 0xFF) << 8 | cp & 0xFF];
                if (position != (changes ? index + 1 : 0)) {
                    wrong.add((lower ? "lower " : "upper ") + Integer.toHexString(cp));
                }
            }
        }
        for (int cp : CaseTables.FINAL_SIGMA.codePoints()) {
            if (CaseTables.LOWER_PAGES[(CaseTables.LOWER_BLOCKS[cp >>> 8] & 0xFF) << 8 | cp & 0xFF] == 0) {
                wrong.add("final sigma " + Integer.toHexString(cp));
            }
        }
        assertEquals(List.of(), wrong);
    }

    /** The non-zero combining classes {@code UnicodeData.txt} states, by code point. No range it
     *  writes as a first and a last line has one. */
    private static Map<Integer, Integer> combiningClasses() throws IOException {
        Map<Integer, Integer> classes = new HashMap<>();
        for (String line : Files.readAllLines(Ucd.file("UnicodeData.txt"))) {
            String[] f = line.split(";", -1);
            int value = Integer.parseInt(f[3]);
            if (value != 0) {
                classes.put(Integer.parseInt(f[0], 16), value);
            }
        }
        return classes;
    }
}
