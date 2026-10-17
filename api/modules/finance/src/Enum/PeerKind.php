<?php

declare(strict_types=1);

namespace Maggie\Finance\Enum;

/**
 * What a transfer with a person is (MAG-453, rule 5 of the spec « Créances,
 * dettes et contacts »). Received: an income, the repayment of créances, or a
 * new dette. Sent: the repayment of dettes, a new créance, or anything else.
 */
enum PeerKind: string
{
    case Income = 'income';
    case Repayment = 'repayment';
    case NewDebt = 'new_debt';
    case NewClaim = 'new_claim';
    case Other = 'other';

    /** Whether the answer fits a received transfer (true) or a sent one (false). */
    public function fits(bool $incoming): bool
    {
        return match ($this) {
            self::Repayment => true,
            self::Income, self::NewDebt => $incoming,
            self::NewClaim, self::Other => !$incoming,
        };
    }

    /** @return list<string> the answers a received or a sent transfer takes */
    public static function valuesFor(bool $incoming): array
    {
        return array_values(array_map(
            static fn (self $kind): string => $kind->value,
            array_filter(self::cases(), static fn (self $kind): bool => $kind->fits($incoming)),
        ));
    }
}
