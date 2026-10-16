<?php

declare(strict_types=1);

namespace Maggie\Finance\Category;

final class RulePattern
{
    private const MAX_WORDS = 3;

    /**
     * Bank labels carry noise — dates, card numbers, receipt ids. Keep the
     * leading words, up to the first one that holds a digit: that is what
     * identifies the merchant.
     */
    public static function fromLabel(string $label): string
    {
        $normalized = trim(preg_replace('/\s+/u', ' ', $label) ?? '');
        if ('' === $normalized) {
            return '';
        }

        $words = [];
        foreach (explode(' ', $normalized) as $word) {
            if (1 === preg_match('/\d/u', $word)) {
                break;
            }
            $words[] = $word;
            if (self::MAX_WORDS === \count($words)) {
                break;
            }
        }

        return [] === $words ? $normalized : implode(' ', $words);
    }
}
