import { WHITE_SPACE } from "./white_space_tables.ts";

/**
 * Whether the code point `codePoint` has the White_Space property, as of Unicode 18.0.0.
 *
 * Not `\s` in a regular expression or `String.prototype.trim`, which leave U+0085 out, take U+FEFF
 * in, and answer for the rest by the Unicode version of the engine.
 */
export function isWhiteSpace(codePoint: number): boolean {
  // A handful of ranges, so a scan in order is as quick as a search.
  for (let at = 0; at < WHITE_SPACE.length; at += 2) {
    if (codePoint < WHITE_SPACE[at]!) {
      return false;
    }
    if (codePoint <= WHITE_SPACE[at + 1]!) {
      return true;
    }
  }
  return false;
}
