<?php

declare(strict_types=1);

namespace Raoh\Notation199x\Internal;

use Raoh\Notation199x\NormalizationForm;

/**
 * What the normalization asks of a form: whether it follows the compatibility mappings as well as
 * the canonical ones, whether it composes, the trivial limit below which every code point is a
 * stable starter of the form, and the form's bit of NormalizationTables::STABLE_PAGES.
 *
 * @internal
 */
final class FormFacts
{
    private function __construct(
        public readonly bool $compatibility,
        public readonly bool $composes,
        public readonly int $limit,
        public readonly int $bit,
    ) {
    }

    public static function of(NormalizationForm $form): self
    {
        return match ($form) {
            NormalizationForm::NFC => new self(false, true, NormalizationTables::NFC_TRIVIAL_LIMIT, 1),
            NormalizationForm::NFD => new self(false, false, NormalizationTables::NFD_TRIVIAL_LIMIT, 2),
            NormalizationForm::NFKC => new self(true, true, NormalizationTables::NFKC_TRIVIAL_LIMIT, 4),
            NormalizationForm::NFKD => new self(true, false, NormalizationTables::NFKD_TRIVIAL_LIMIT, 8),
        };
    }
}
