// Reading the vectors in the repository's `suite` directory, as `suite/README.md` states their
// format.
//
// The directory is outside this package: a package made from `ts/` holds what is under it, so a
// copy of the package npm fetched has none. A test that reads it runs in a checkout, skips where it
// is not there, and fails instead where `NOTATION199X_REQUIRE_SUITE` is set, as CI sets it, so that
// a checkout missing it is not taken for a package without it.

import assert from "node:assert/strict";
import { existsSync, readFileSync } from "node:fs";
import { dirname, join } from "node:path";
import type { TestContext } from "node:test";

const REQUIRE_SUITE = "NOTATION199X_REQUIRE_SUITE";
const ROOT = join(packageRoot(import.meta.dirname), "..");

/**
 * The directory of the package `from` is in, the nearest that holds a package.json: the tests run
 * from test/ as TypeScript, and from the directory they are compiled into as JavaScript.
 */
function packageRoot(from: string): string {
  for (let at = from; ; at = dirname(at)) {
    if (existsSync(join(at, "package.json"))) {
      return at;
    }
    if (dirname(at) === at) {
      throw new Error(`no package.json above ${from}`);
    }
  }
}

/**
 * The path of `name` under the repository's root, or `undefined` where the file is not there and
 * the environment does not require it, in which case the test is skipped.
 */
export function repositoryFile(name: string, t: TestContext): string | undefined {
  const path = join(ROOT, name);
  if (existsSync(path)) {
    return path;
  }
  if (process.env[REQUIRE_SUITE] !== undefined) {
    assert.fail(`${path} is missing, and ${REQUIRE_SUITE} is set: the tests run in ts/ of a checkout`);
  }
  t.skip(`${path} is outside this package, and is read only in a checkout of the repository`);
  return undefined;
}

/** One line of vectors, and which of its fields have been read. */
export class Line {
  readonly #where: string;
  readonly #fields: readonly string[];
  readonly #read: boolean[];
  #wrong: string | undefined;

  constructor(where: string, fields: readonly string[]) {
    this.#where = where;
    this.#fields = fields;
    this.#read = fields.map(() => false);
  }

  #take(i: number): string {
    this.#read[i] = true;
    return this.#fields[i]!;
  }

  #mistake(i: number, what: string): void {
    this.#wrong ??= `${this.#where}: field ${i + 1} is ${JSON.stringify(this.#fields[i])}, not ${what}`;
  }

  /** Reads a field a line leaves empty where it asserts nothing there. */
  empty(i: number): void {
    if (this.#take(i) !== "") {
      this.#mistake(i, "empty");
    }
  }

  /** Reads a field as the text it writes: scalar values in hex, separated by spaces. */
  text(i: number): string {
    const field = this.#take(i);
    let text = "";
    for (const each of field.split(" ").filter((part) => part !== "")) {
      const scalar = /^[0-9A-F]{4,6}$/.test(each) ? Number.parseInt(each, 16) : -1;
      if (scalar < 0 || scalar > 0x10FFFF || (scalar >= 0xD800 && scalar <= 0xDFFF)) {
        this.#mistake(i, "a text of scalar values in hex");
        return "";
      }
      text += String.fromCodePoint(scalar);
    }
    return text;
  }

  /** Reads a field as the one scalar value it writes. */
  scalar(i: number): number {
    const scalars = [...this.text(i)];
    if (scalars.length !== 1) {
      this.#mistake(i, "one scalar value");
      return 0;
    }
    return scalars[0]!.codePointAt(0)!;
  }

  /** Reads a field as an unsigned decimal. */
  number(i: number): number {
    const field = this.#take(i);
    if (!/^(0|[1-9][0-9]{0,14})$/.test(field)) {
      this.#mistake(i, "an unsigned decimal");
      return 0;
    }
    return Number(field);
  }

  /** Reads a field as one of `names`. */
  oneOf<const N extends string>(i: number, names: readonly N[]): N {
    const field = this.#take(i);
    const found = names.find((name) => name === field);
    if (found === undefined) {
      this.#mistake(i, `one of ${names.join(", ")}`);
      return names[0]!;
    }
    return found;
  }

  /** Reads a field as one of `names`, or `undefined` where it is empty. */
  oneOfOrNothing<const N extends string>(i: number, names: readonly N[]): N | undefined {
    if (this.#fields[i] === "") {
      this.empty(i);
      return undefined;
    }
    return this.oneOf(i, names);
  }

  /** Reads a field as `true` or `false`. */
  yesOrNo(i: number): boolean {
    const field = this.#take(i);
    if (field !== "true" && field !== "false") {
      this.#mistake(i, "true or false");
    }
    return field === "true";
  }

  /** What is wrong with how the line is written, or `undefined`: a field not read, or misread. */
  mistake(): string | undefined {
    const unread = this.#read.indexOf(false);
    return unread >= 0 ? `${this.#where}: field ${unread + 1} is not read` : this.#wrong;
  }
}

/**
 * Holds the implementation to every line of `name`, a path under the repository's root: `check`
 * reads every field of a line and answers what is wrong on it, or `undefined`. A field no check
 * reads fails the test, so nothing written in a file goes unchecked. Every line is checked, and the
 * test fails at the end with every line that answered otherwise.
 *
 * @returns how many lines were read, 0 where the test was skipped
 */
export function eachLine(t: TestContext, name: string, fields: number,
  check: (line: Line) => string | undefined): number {
  const path = repositoryFile(name, t);
  if (path === undefined) {
    return 0;
  }
  const data = readFileSync(path, "utf-8");
  const lines = (data.endsWith("\n") ? data.slice(0, -1) : data).split("\n");
  let sourced = false;
  let vectors = 0;
  const failures: string[] = [];
  lines.forEach((text, n) => {
    if (text === "# Source:" && lines[n + 1]?.startsWith("#   ")) {
      sourced = true;
    }
    if (text === "" || text.startsWith("#")) {
      return;
    }
    const where = `${name}:${n + 1}`;
    assert.ok(sourced, `${name} names no source before its first vector`);
    const split = text.split(";").map((field) => field.replace(/^ +| +$/g, ""));
    assert.equal(split.length, fields, `${where} has ${split.length} fields, not ${fields}`);
    const line = new Line(where, split);
    const said = check(line);
    const mistake = line.mistake();
    if (mistake !== undefined) {
      assert.fail(mistake);
    }
    if (said !== undefined) {
      failures.push(`${where}: ${said}`);
    }
    vectors++;
  });
  assert.ok(vectors > 0, `${name} holds no vectors`);
  assert.deepEqual(failures, [], `${failures.length} lines answered otherwise`);
  return vectors;
}

/** `text` as a message shows it: printable ASCII as itself, and every other code point as `<U+XXXX>`. */
export function shown(text: string): string {
  let out = "";
  for (const each of text) {
    const cp = each.codePointAt(0)!;
    out += cp >= 0x20 && cp < 0x7F ? each : `<U+${cp.toString(16).toUpperCase().padStart(4, "0")}>`;
  }
  return JSON.stringify(out);
}
