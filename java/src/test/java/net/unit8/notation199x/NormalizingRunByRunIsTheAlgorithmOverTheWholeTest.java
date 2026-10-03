package net.unit8.notation199x;

import org.junit.jupiter.api.Test;

import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

/**
 * {@code Normalization} keeps the text as it is where it reads stable starters, and runs the
 * algorithm only from the stable starter before a code point that is not one up to the next stable
 * starter. That is right only where the answer is what the algorithm gives over the whole text, so
 * the two are held against each other here, in every form and within every bound around the answer's
 * length.
 *
 * <p>The texts mix stable starters of several scripts with what is not one: marks, composites
 * that decompose in one form and not another, compatibility characters, Hangul jamo that compose by
 * arithmetic and the kana voicing marks, so that a text goes in and out of the algorithm many times.
 */
class NormalizingRunByRunIsTheAlgorithmOverTheWholeTest {

    private static final int[] ALPHABET = {
            'a', 'e', 'A', ' ', '.', 0x00E9, 0x00C7,             // stable in a canonical form
            0x3042, 0x304B, 0x30AB, 0x65E5, 0x672C,              // kana and ideographs
            0xAC00, 0xAC01, 0xD55C,                              // Hangul syllables
            0x0300, 0x0301, 0x0323, 0x0327, 0x05B0,              // marks
            0x3099, 0x309A, 0x309B,                              // kana voicing marks
            0x1100, 0x1161, 0x11A8,                              // Hangul L, V and T
            0xFB01, 0x3231, 0xFF76, 0xFF9E, 0x00A0, 0x2126,      // compatibility characters, a singleton
            0x0B47, 0x0B3E, 0x1D15E, 0x0344};                    // a starter second, marks that decompose

    @Test
    void runByRunIsTheAlgorithmOverTheWholeTextInEveryForm() {
        Random random = new Random(1999);
        for (int n = 0; n < 20000; n++) {
            StringBuilder text = new StringBuilder();
            int length = random.nextInt(40);
            for (int i = 0; i < length; i++) {
                int cp = ALPHABET[random.nextInt(ALPHABET.length)];
                // Runs of stable starters long enough to be kept, between what is not.
                int times = random.nextInt(4) == 0 ? 1 + random.nextInt(6) : 1;
                for (int t = 0; t < times; t++) {
                    text.appendCodePoint(cp);
                }
            }
            String s = text.toString();
            for (Normalization.Form form : Normalization.Form.values()) {
                String whole = Normalization.normalizeFromStart(form, s, Long.MAX_VALUE);
                assertEquals(whole, Normalization.normalize(form, s),
                        () -> form + " " + s.codePoints().mapToObj(Integer::toHexString).toList());
                long answer = whole.codePointCount(0, whole.length());
                for (long longest = Math.max(0, answer - 2); longest <= answer + 1; longest++) {
                    long bound = longest;
                    assertEquals(longest >= answer ? whole : null, Normalization.normalizeWithin(form, s, longest),
                            () -> form + " within " + bound + " " + s.codePoints().mapToObj(Integer::toHexString).toList());
                }
            }
        }
    }

    /** Text that is its own normalization is answered with itself, whether or not the algorithm went
     *  over part of it. */
    @Test
    void textThatIsItsOwnNormalizationIsAnsweredWithItself() {
        String japanese = "日本語のテキスト、ガギグ。".repeat(10);
        String korean = "한국어 텍스트".repeat(10);
        String latin = "Renée Ångström à l'école".repeat(10);
        for (String s : new String[] {japanese, korean, latin}) {
            assertSame(s, Normalization.normalize(Normalization.Form.NFC, s));
        }
        assertSame(japanese, Normalization.normalize(Normalization.Form.NFKC, japanese));
        // A mark that is in order after a starter it does not compose with: the algorithm goes over
        // it, and changes nothing.
        String marked = "abḉdef".repeat(10);
        assertSame(marked, Normalization.normalize(Normalization.Form.NFD, marked));
        // A text the algorithm changes in one place keeps the rest as it is.
        assertEquals(japanese + "が" + japanese,
                Normalization.normalize(Normalization.Form.NFC, japanese + "が" + japanese));
    }

    @Test
    void textThatMapsToItselfIsAnsweredWithItself() {
        for (String s : new String[] {"日本語のテキスト", "straße", "abc def 123", ""}) {
            assertSame(s, CaseConversion.lowercase(s));
        }
        for (String s : new String[] {"日本語のテキスト", "ABC DEF 123"}) {
            assertSame(s, CaseConversion.uppercase(s));
        }
    }
}
