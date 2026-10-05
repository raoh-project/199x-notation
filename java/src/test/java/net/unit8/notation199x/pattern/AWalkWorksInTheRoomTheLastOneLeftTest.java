package net.unit8.notation199x.pattern;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A walk as sets of states works in the room the last walk to finish left, as large as the machine,
 * and makes none of its own; and the rounds it marks states with go on from that walk's, past where
 * they come round to nought, with the same answers.
 */
class AWalkWorksInTheRoomTheLastOneLeftTest {

    /** Each way a walk as sets of states goes, over a machine of 200,000 states, makes its room the
     *  first time and not after. */
    @Test
    void aWalkAfterTheFirstMakesNoRoom() {
        Automaton machine = shaped("a{200000}");
        StringPattern keeping = StringPattern.of(machine);
        StringPattern moving = StringPattern.of(machine, StringPattern.Budget.DEFAULT.keeping(0));
        assertEquals(StringPattern.Way.SETS_KEPT, keeping.way());
        assertEquals(StringPattern.Way.EVERY_STATE, moving.way());
        for (StringPattern pattern : List.of(keeping, moving)) {
            pattern.matches("a");
            // The steps worked out are forgotten, so that the walk keeping sets works one out
            // again, in a room.
            pattern.forgetSteps();
            long made = made(() -> pattern.matches("a"));
            assertTrue(made < 100_000, pattern.way() + " made " + made + " bytes");
        }
    }

    /** Walks whose rounds come round to nought answer as walks on a new pattern do. */
    @Test
    void roundsThatComeRoundToNoughtAnswerTheSame() {
        Automaton machine = shaped("(?:a|b)*a(?:a|b){3}");
        List<String> subjects = List.of("", "a", "abbb", "aabab", "babba", "bbbbbbbbb", "abababab",
                "aaaaaaaa", "baaa");
        for (StringPattern.Budget budget : List.of(StringPattern.Budget.DEFAULT,
                StringPattern.Budget.DEFAULT.keeping(0), StringPattern.Budget.DEFAULT.keeping(2))) {
            StringPattern pattern = StringPattern.of(machine, budget);
            pattern.leaveRoomAt(-3);
            for (int round = 0; round < 3; round++) {
                for (String subject : subjects) {
                    pattern.forgetSteps();
                    assertEquals(StringPattern.of(machine, budget).matches(subject),
                            pattern.matches(subject), budget + " " + subject);
                }
            }
        }
    }

    /** The bytes {@code match} made on this thread. */
    private static long made(Runnable match) {
        com.sun.management.ThreadMXBean threads =
                (com.sun.management.ThreadMXBean) java.lang.management.ManagementFactory.getThreadMXBean();
        long before = threads.getCurrentThreadAllocatedBytes();
        match.run();
        return threads.getCurrentThreadAllocatedBytes() - before;
    }

    private static Automaton shaped(String pattern) {
        PatternRead.Read read = (PatternRead.Read) PatternParser.read(pattern);
        return PatternMachine.of(read.meaning()).shaped();
    }
}
