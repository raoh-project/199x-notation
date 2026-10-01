package net.unit8.notation199x.pattern;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * What the anchors in a pattern come to, given that the whole of it must match the whole string.
 *
 * <p>Whole-string matching is what gives an anchor an answer. {@code ^} asks to be at the start of
 * the string, so it is satisfied by every string where nothing before it can take a symbol and by
 * none where everything before it must — the empty string in the first case and
 * {@link PatternMeaning.Never} in the second. {@code $} is the same question about the end.
 *
 * <p><b>Null where neither holds.</b> {@code (a|)^b} has something before the anchor that sometimes
 * takes a symbol and sometimes does not, and the strings it accepts are the ones that took the
 * second way — an answer neither arm above gives, and one the pattern language does not have. So
 * the pattern is refused rather than read as one of them. The same for an anchor under a
 * repetition, where how many copies precede it is not a thing the shape says.
 *
 * <p>Asked once, by the reader, because it is one rule. What leaves the reader is a
 * {@link PatternMeaning}, which has no anchor in it, so nothing downstream asks the question again.
 *
 * <p><b>No recursion.</b> The reader asks this of text nested as deeply as it is long, before any
 * limit says it is too deep to take, because whether an anchor can be placed is part of whether the
 * text is a pattern at all. So the tree is walked with stacks of its own: once from the leaves up,
 * for what each part may and must take and whether it holds an anchor, and once from the root
 * down, for where each part stands and what it comes to.
 */
final class Anchors {

    private Anchors() {
    }

    /** Whether an anchor is at the end it is asking about, as far as the shape says. */
    private enum Where { YES, NO, UNSETTLED }

    /**
     * What a part is, as far as the anchors around it ask.
     *
     * @param may   whether it accepts any string of one symbol or more
     * @param must  whether every string it accepts has a symbol in it
     * @param holds whether it holds an anchor
     */
    private record Facts(boolean may, boolean must, boolean holds) {}

    /** The meaning of {@code written} with every anchor read as what it comes to, or null where one
     *  cannot be settled. */
    static @Nullable PatternMeaning placed(WrittenPattern written) {
        Map<WrittenPattern, Facts> facts = facts(written);
        // Each task is a part to place where it stands, or a part whose parts have been placed and
        // are waiting on the results to be put together.
        List<Task> tasks = new ArrayList<>();
        List<PatternMeaning> results = new ArrayList<>();
        tasks.add(new Task(written, Where.YES, Where.YES, false));
        while (!tasks.isEmpty()) {
            Task task = tasks.removeLast();
            if (task.together()) {
                results.add(together(task.written(), results));
                continue;
            }
            switch (task.written()) {
                case WrittenPattern.Meant it -> results.add(it.meaning());
                case WrittenPattern.Anchor it -> {
                    PatternMeaning made = anchor(it, it.end() ? task.atEnd() : task.atStart());
                    if (made == null) {
                        return null;
                    }
                    results.add(made);
                }
                // Every arm of a choice begins where the choice begins and ends where it ends.
                case WrittenPattern.EitherOf it -> {
                    tasks.add(task.putTogether());
                    for (int at = it.arms().size() - 1; at >= 0; at--) {
                        tasks.add(new Task(it.arms().get(at), task.atStart(), task.atEnd(), false));
                    }
                }
                case WrittenPattern.InTurn it -> {
                    tasks.add(task.putTogether());
                    Where[][] sides = sides(it, facts, task.atStart(), task.atEnd());
                    for (int at = it.parts().size() - 1; at >= 0; at--) {
                        tasks.add(new Task(it.parts().get(at), sides[at][0], sides[at][1], false));
                    }
                }
                case WrittenPattern.Repeated it when !factsOf(facts, it.what()).holds() -> {
                    tasks.add(task.putTogether());
                    tasks.add(new Task(it.what(), task.atStart(), task.atEnd(), false));
                }
                // One copy is the thing itself and stands where the repetition stands. Any other
                // count leaves how many copies come before the anchor to the string being matched,
                // which is not a thing the shape of the pattern answers.
                case WrittenPattern.Repeated it when it.least() == 1 && it.most() == 1 ->
                        tasks.add(new Task(it.what(), task.atStart(), task.atEnd(), false));
                case WrittenPattern.Repeated _ -> {
                    return null;
                }
            }
        }
        return results.getFirst();
    }

    /**
     * A part to place, standing where {@code atStart} and {@code atEnd} say, or, where
     * {@code together}, one whose parts are placed and whose meaning is to be put together from
     * them.
     */
    private record Task(WrittenPattern written, Where atStart, Where atEnd, boolean together) {

        Task putTogether() {
            return new Task(written, atStart, atEnd, true);
        }
    }

    private static @Nullable PatternMeaning anchor(WrittenPattern.Anchor it, Where where) {
        return switch (where) {
            case YES -> new PatternMeaning.Nothing();
            // {@code ^} asks to be at the start of the string and there is one such place, so
            // anything that must take a symbol before it leaves no string at all. A {@code $} with
            // something after it that must take a symbol is refused rather than read the same
            // way: the language keeps the set of patterns it reads, and that set has no pattern of
            // this shape.
            case NO -> it.end() ? null : new PatternMeaning.Never();
            case UNSETTLED -> null;
        };
    }

    /** The meaning of {@code written}, from the meanings of its parts, which are the last of
     *  {@code results} and are taken off it. */
    private static PatternMeaning together(WrittenPattern written, List<PatternMeaning> results) {
        return switch (written) {
            case WrittenPattern.EitherOf it -> new PatternMeaning.EitherOf(last(results, it.arms().size()));
            case WrittenPattern.InTurn it -> {
                // An anchor that asks for nothing leaves nothing in the sequence, so `^abc$` means
                // what `abc` means and is the same tree.
                List<PatternMeaning> parts = new ArrayList<>();
                for (PatternMeaning made : last(results, it.parts().size())) {
                    if (!(made instanceof PatternMeaning.Nothing)) {
                        parts.add(made);
                    }
                }
                yield switch (parts.size()) {
                    case 0 -> new PatternMeaning.Nothing();
                    case 1 -> parts.get(0);
                    default -> new PatternMeaning.InTurn(parts);
                };
            }
            case WrittenPattern.Repeated it ->
                    new PatternMeaning.Repeated(results.removeLast(), it.least(), it.most());
            case WrittenPattern.Meant _, WrittenPattern.Anchor _ ->
                    throw new IllegalStateException("a leaf has no parts to put together");
        };
    }

    /** The last {@code count} of {@code results}, in order, taken off it. */
    private static List<PatternMeaning> last(List<PatternMeaning> results, int count) {
        List<PatternMeaning> tail = results.subList(results.size() - count, results.size());
        List<PatternMeaning> out = List.copyOf(tail);
        tail.clear();
        return out;
    }

    /**
     * Where each part of a sequence stands: at the start of the string where nothing before it
     * takes a symbol and the sequence is there, and not there where everything before it must; the
     * same for the end. What stands before each part and after it is gathered once from each end.
     * Asked afresh of every part, the sides are read again for each of them, and a literal written
     * out a symbol at a time costs its length squared.
     */
    private static Where[][] sides(WrittenPattern.InTurn it, Map<WrittenPattern, Facts> facts,
                                   Where atStart, Where atEnd) {
        int count = it.parts().size();
        boolean[] mayBefore = new boolean[count + 1];
        boolean[] mustBefore = new boolean[count + 1];
        mustBefore[0] = true;
        for (int at = 0; at < count; at++) {
            Facts part = factsOf(facts, it.parts().get(at));
            mayBefore[at + 1] = mayBefore[at] || part.may();
            mustBefore[at + 1] = mustBefore[at] && part.must();
        }
        boolean[] mayAfter = new boolean[count + 1];
        boolean[] mustAfter = new boolean[count + 1];
        mustAfter[count] = true;
        for (int at = count - 1; at >= 0; at--) {
            Facts part = factsOf(facts, it.parts().get(at));
            mayAfter[at] = mayAfter[at + 1] || part.may();
            mustAfter[at] = mustAfter[at + 1] && part.must();
        }
        Where[][] out = new Where[count][];
        for (int at = 0; at < count; at++) {
            out[at] = new Where[] {
                    beyond(mayBefore[at], mustBefore[at], atStart),
                    beyond(mayAfter[at + 1], mustAfter[at + 1], atEnd)};
        }
        return out;
    }

    /**
     * Where a part stands, given what is on that side of it and where they all stand together.
     *
     * <p>Nothing on that side takes a symbol, so the part stands where they all do. Everything on
     * that side must take one, so it does not. Anything in between and the answer belongs to a
     * string rather than to the pattern.
     *
     * @param anyTakes whether something on that side may take a symbol
     * @param allTake  whether everything on that side must take one
     */
    private static Where beyond(boolean anyTakes, boolean allTake, Where outer) {
        if (!anyTakes) {
            return outer;
        }
        return allTake ? Where.NO : Where.UNSETTLED;
    }

    /** The facts of every part of {@code written}, worked out from the leaves up. */
    private static Map<WrittenPattern, Facts> facts(WrittenPattern written) {
        Map<WrittenPattern, Facts> out = new IdentityHashMap<>();
        // A part is pushed once to have its parts worked out and once more, below them, to be
        // worked out from theirs.
        List<WrittenPattern> pending = new ArrayList<>();
        List<Boolean> partsDone = new ArrayList<>();
        pending.add(written);
        partsDone.add(false);
        while (!pending.isEmpty()) {
            WrittenPattern part = pending.removeLast();
            boolean ready = partsDone.removeLast();
            List<WrittenPattern> inside = inside(part);
            if (!ready && !inside.isEmpty()) {
                pending.add(part);
                partsDone.add(true);
                for (WrittenPattern each : inside) {
                    pending.add(each);
                    partsDone.add(false);
                }
                continue;
            }
            out.put(part, factsOf(part, out));
        }
        return out;
    }

    private static List<WrittenPattern> inside(WrittenPattern written) {
        return switch (written) {
            case WrittenPattern.Meant _, WrittenPattern.Anchor _ -> List.of();
            case WrittenPattern.InTurn it -> it.parts();
            case WrittenPattern.EitherOf it -> it.arms();
            case WrittenPattern.Repeated it -> List.of(it.what());
        };
    }

    /** The facts of {@code part}, which are worked out before those of whatever holds it. */
    private static Facts factsOf(Map<WrittenPattern, Facts> known, WrittenPattern part) {
        Facts facts = known.get(part);
        if (facts == null) {
            throw new IllegalStateException("a part is asked about before its facts are known");
        }
        return facts;
    }

    private static Facts factsOf(WrittenPattern written, Map<WrittenPattern, Facts> known) {
        return switch (written) {
            case WrittenPattern.Meant it -> new Facts(mayTake(it.meaning()), mustTake(it.meaning()), false);
            case WrittenPattern.Anchor _ -> new Facts(false, false, true);
            case WrittenPattern.InTurn it -> new Facts(
                    it.parts().stream().anyMatch(each -> factsOf(known, each).may()),
                    it.parts().stream().anyMatch(each -> factsOf(known, each).must()),
                    it.parts().stream().anyMatch(each -> factsOf(known, each).holds()));
            case WrittenPattern.EitherOf it -> new Facts(
                    it.arms().stream().anyMatch(each -> factsOf(known, each).may()),
                    it.arms().stream().allMatch(each -> factsOf(known, each).must()),
                    it.arms().stream().anyMatch(each -> factsOf(known, each).holds()));
            case WrittenPattern.Repeated it -> {
                Facts what = factsOf(known, it.what());
                yield new Facts(
                        (it.most() == PatternMeaning.Repeated.NO_CEILING || it.most() > 0) && what.may(),
                        it.least() > 0 && what.must(),
                        what.holds());
            }
        };
    }

    /** Whether it accepts any string of one symbol or more. A meaning the reader writes is a set of
     *  symbols or nothing, so this is never deep. */
    private static boolean mayTake(PatternMeaning meaning) {
        return switch (meaning) {
            case PatternMeaning.Nothing _, PatternMeaning.Never _ -> false;
            case PatternMeaning.Symbols _ -> true;
            case PatternMeaning.InTurn it -> it.parts().stream().anyMatch(Anchors::mayTake);
            case PatternMeaning.EitherOf it -> it.arms().stream().anyMatch(Anchors::mayTake);
            case PatternMeaning.Repeated it ->
                    (it.unbounded() || it.most() > 0) && mayTake(it.what());
        };
    }

    /** Whether every string it accepts has a symbol in it. */
    private static boolean mustTake(PatternMeaning meaning) {
        return switch (meaning) {
            case PatternMeaning.Nothing _ -> false;
            // It accepts no string, so none of the strings it accepts is the empty one — which is
            // the answer that leaves an anchor beyond it settled rather than unsettled.
            case PatternMeaning.Never _, PatternMeaning.Symbols _ -> true;
            case PatternMeaning.InTurn it -> it.parts().stream().anyMatch(Anchors::mustTake);
            case PatternMeaning.EitherOf it -> it.arms().stream().allMatch(Anchors::mustTake);
            case PatternMeaning.Repeated it -> it.least() > 0 && mustTake(it.what());
        };
    }
}
