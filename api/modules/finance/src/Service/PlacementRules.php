<?php

declare(strict_types=1);

namespace Maggie\Finance\Service;

use Maggie\Finance\Entity\Placement;
use Maggie\Finance\Enum\PlacementFrequency;

/**
 * What a placement is held to whichever door it comes through (API, MCP).
 * Every refusal is a DomainException, a 400 for the API and an error for the
 * tool.
 */
final class PlacementRules
{
    public function frequency(string $value): PlacementFrequency
    {
        return PlacementFrequency::tryFrom($value)
            ?? throw new \DomainException("Unknown frequency: {$value}. Use monthly, quarterly, yearly or once.");
    }

    public function assertName(string $name): void
    {
        if ('' === trim($name)) {
            throw new \DomainException('name must not be blank.');
        }
    }

    /** The whole placement, once the fields given are merged with the current ones. */
    public function assert(int $initial, int $deposit, int $annualRate, int $years): void
    {
        if ($initial < 0 || $deposit < 0 || $initial > Placement::MAX_CENTS || $deposit > Placement::MAX_CENTS) {
            throw new \DomainException('initial and deposit are integer cents, from 0 to 1000000000 (10 million euros).');
        }
        if (0 === $initial && 0 === $deposit) {
            throw new \DomainException(Placement::NOTHING_PAID);
        }
        if ($annualRate < 0 || $annualRate > Placement::MAX_RATE_BASIS_POINTS) {
            throw new \DomainException('annualRate is in basis points, from 0 to 10000: 600 is 6 %.');
        }
        if ($years < 1 || $years > Placement::MAX_YEARS) {
            throw new \DomainException('years must be a whole number from 1 to 60.');
        }
    }
}
