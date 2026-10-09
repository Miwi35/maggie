<?php

declare(strict_types=1);

namespace Maggie\Finance\Specification;

/**
 * Whether a code names a currency an account can be held in (MAG-376).
 *
 * A bank that has nothing to say answers « XXX », which is well formed and
 * means « no currency » — taking it as one made every total and the matching
 * of accounts wrong. Letters alone do not make a currency.
 */
final class RealCurrency
{
    public const DEFAULT = 'EUR';

    /**
     * ISO 4217 reserves the X codes for what is not a national currency. Most
     * are not money at all: no currency, tests, metals, fund and unit codes.
     * Four are real money — XAF, XOF, XCD and XPF, the franc of the Pacific
     * territories among them — and stay allowed.
     */
    private const NOT_A_CURRENCY = [
        'XXX', 'XTS', 'XAU', 'XAG', 'XPT', 'XPD', 'XDR', 'XBA', 'XBB', 'XBC', 'XBD',
        'XSU', 'XUA', 'XFU', 'XRE',
    ];

    public static function isReal(mixed $code): bool
    {
        return \is_string($code)
            && 1 === preg_match('/^[A-Z]{3}$/', $code)
            && !\in_array($code, self::NOT_A_CURRENCY, true);
    }

    /** The first candidate that is a real currency, else the euro. */
    public static function resolve(mixed ...$candidates): string
    {
        foreach ($candidates as $candidate) {
            if (self::isReal($candidate)) {
                return $candidate;
            }
        }

        return self::DEFAULT;
    }
}
