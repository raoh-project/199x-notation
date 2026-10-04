// The rules for reading text that Raoh and Souther share: the Unicode 18.0.0 White_Space set, order
// and length in Unicode scalar values, and the lexical grammar of temporal text.
//
// Each rule answers the same way whatever engine it runs on. No rule asks the engine's Unicode
// support, such as `\s` in a regular expression, `String.prototype.trim` or `Intl`, or its `Date`:
// their answers follow the engine's release. The tables are generated from the Unicode Character
// Database by `gen/Generate.java` in the repository, and the rules are held to the vectors in its
// `suite` directory, the same vectors every other implementation is held to.

export {
  PATTERN_LIMITS,
  readPattern,
  type Pattern,
  type PatternBeyond,
  type PatternLimit,
  type PatternRead,
  type PatternRefusal,
  type PatternRefused,
} from "./pattern.ts";
export { compare, illFormedAt, scalarCount } from "./text.ts";
export {
  INSTANT_MAX,
  INSTANT_MIN,
  YEAR_MAX,
  YEAR_MIN,
  checkTemporal,
  readDate,
  readDateTime,
  readInstant,
  readOffsetDateTime,
  readTime,
  type TemporalAnswer,
  type TemporalDate,
  type TemporalDateTime,
  type TemporalInstant,
  type TemporalKind,
  type TemporalOffsetDateTime,
  type TemporalRead,
  type TemporalRefusal,
  type TemporalTime,
} from "./temporal.ts";
export { isWhiteSpace } from "./white_space.ts";
