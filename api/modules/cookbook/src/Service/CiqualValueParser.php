<?php

declare(strict_types=1);

namespace Maggie\Cookbook\Service;

class CiqualValueParser
{
    public function parse(string $rawValue): ?float
    {
        $trimmed = trim($rawValue);

        if ($trimmed === '' || $trimmed === '-') {
            return null;
        }

        if (strtolower($trimmed) === 'traces') {
            return 0.0;
        }

        // Handle "< X" values (e.g., "< 0.5" → 0.25)
        if (str_starts_with($trimmed, '<')) {
            $number = trim(ltrim($trimmed, '<'));
            $number = str_replace(',', '.', $number);
            if (is_numeric($number)) {
                return (float) $number / 2;
            }

            return null;
        }

        // French comma decimal separator
        $normalized = str_replace(',', '.', $trimmed);

        if (is_numeric($normalized)) {
            return (float) $normalized;
        }

        return null;
    }
}
