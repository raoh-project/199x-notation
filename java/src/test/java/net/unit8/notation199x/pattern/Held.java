package net.unit8.notation199x.pattern;

import java.util.Collection;
import java.util.Objects;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;

/**
 * The strings a pattern or a list of words accepts, as the one smallest machine for them: what a
 * test compares two patterns by and asks a string of.
 */
final class Held {

    private final Automaton machine;

    private Held(Automaton canonical) {
        this.machine = Objects.requireNonNull(canonical, "the machine is within what a test allows");
    }

    /** What a test is allowed to build: far past anything written in one. */
    static Meter roomy() {
        return new Meter(50_000, 200_000, 50_000_000);
    }

    static Held by(String regex) {
        PatternRead.Read read = assertInstanceOf(PatternRead.Read.class, PatternParser.read(regex), regex);
        return by(read.meaning(), roomy());
    }

    static Held by(PatternMeaning meaning, Meter meter) {
        return new Held(canonical(meaning, meter));
    }

    static Held words(Collection<String> words, Meter meter) {
        Automaton made = Automaton.ofWords(words, meter);
        return new Held(made == null ? null : made.canonical(meter));
    }

    /**
     * {@code machine}, a machine made deterministic, as an image of P1 that says it is: what an
     * earlier release wrote and every release reads, and the one way a machine held as its steps
     * comes to be walked one state at a time. Written as P1 is now written and said deterministic,
     * which it is and which reading it holds it to.
     */
    static java.util.List<String> saidDeterministicInP1(Automaton machine) {
        String written = String.join("",
                ((PatternImage.Written) PatternImages.p1(machine)).strings());
        if (!written.startsWith("P1,0,")) {
            throw new IllegalStateException("an image of P1 is written as no deterministic machine");
        }
        return java.util.List.of("P1,1," + written.substring("P1,0,".length()));
    }

    /** The smallest machine for {@code meaning}, or null where it is past {@code meter}. */
    static Automaton canonical(PatternMeaning meaning, Meter meter) {
        Automaton shaped = Automaton.of(meaning, meter);
        return shaped == null ? null : shaped.canonical(meter);
    }

    boolean has(String value) {
        return machine.walks(value);
    }

    boolean isEmpty() {
        return machine.holdsNothing();
    }

    Held not() {
        return new Held(machine.not(roomy()));
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof Held it && machine.sameAs(it.machine);
    }

    @Override
    public int hashCode() {
        return machine.shape();
    }

    @Override
    public String toString() {
        String some = machine.shortest();
        return some == null ? "nothing" : "\"" + some + "\" and such";
    }
}
