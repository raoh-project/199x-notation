# raoh/199x-notation

The PHP implementation of the rules for reading text that
[Raoh](https://github.com/raoh-project) and [Souther](https://github.com/souther-lang/souther)
share: Unicode 18.0.0 default case conversion and normalization, the `White_Space` set, order and
length in Unicode scalar values, the lexical grammar of temporal text, and the pattern language.

This package is developed in `php/` of
[raoh-project/199x-notation](https://github.com/raoh-project/199x-notation), beside the Java, Go and
Rust implementations, and is held to the same test vectors. Packagist reads it from
[raoh-project/199x-notation-php](https://github.com/raoh-project/199x-notation-php), a mirror that CI
writes on each release and nobody commits to. Issues and pull requests go to 199x-notation.

```sh
composer require raoh/199x-notation
```

It needs a 64-bit PHP 8 from 8.2 on, as `composer.json` requires it (`"php-64bit": "^8.2"`): a
PHP 9 is taken once it has been tested. It needs no extension: no rule asks mbstring, intl, iconv or
the date functions, whose answers follow the Unicode version and the parsers of the PHP they were
built with. PCRE is asked only whether bytes are UTF-8.

## What it has

Every rule is stated of text that is a sequence of Unicode scalar values. A PHP string is bytes, so
`ScalarValues::invalidUtf8At` is the question a caller asks before it takes text in. The other
functions take text that is valid UTF-8 and do not ask it, apart from `Pattern::read`, which refuses
such text, and `Pattern::matches`, which accepts none.

```php
use Raoh\Notation199x\CaseConversion;
use Raoh\Notation199x\Normalization;
use Raoh\Notation199x\NormalizationForm;
use Raoh\Notation199x\Pattern;
use Raoh\Notation199x\ScalarValues;
use Raoh\Notation199x\TemporalKind;
use Raoh\Notation199x\TemporalText;
use Raoh\Notation199x\WhiteSpace;

ScalarValues::invalidUtf8At("ab\xff");                          // 2
ScalarValues::count('日本語');                                   // 3
CaseConversion::lowercase('ΟΣ');                               // 'ος'
CaseConversion::uppercaseWithin('straße', 6);                  // null: 'STRASSE' is 7 long
Normalization::normalize(NormalizationForm::NFKD, 'ﬃ');        // 'ffi'
WhiteSpace::contains(0x3000);                                  // true
TemporalText::check(TemporalKind::Instant, '2016-12-31T23:59:60Z'); // TemporalAnswer::LeapSecond

$read = Pattern::read('[0-9]{3}-[0-9]{4}');  // a Pattern, a PatternRefused or a PatternBeyond
if ($read instanceof Pattern) {
    $read->matches('123-4567');               // true: the whole string is matched
}
```

A pattern is the language the specifications define, not PCRE's. It is matched against the whole
string, and in time linear in the string whatever the pattern.

raoh-php is what this package is written for. A caller of raoh-php is not meant to call it, and
raoh-php keeps its types out of its own API.

## License

[Apache License 2.0](https://github.com/raoh-project/199x-notation/blob/main/LICENSE)
