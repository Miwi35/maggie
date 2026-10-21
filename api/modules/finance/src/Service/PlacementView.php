<?php

declare(strict_types=1);

namespace Maggie\Finance\Service;

use Maggie\Finance\Entity\Placement;

/** A placement as the MCP tool answers it: its fields and what it would give. */
final class PlacementView
{
    /** @return array<string, mixed> */
    public static function of(Placement $placement): array
    {
        return [
            'id' => (string) $placement->getId(),
            'name' => $placement->getName(),
            'initial' => $placement->getInitial(),
            'deposit' => $placement->getDeposit(),
            'frequency' => $placement->getFrequency()->value,
            'annualRate' => $placement->getAnnualRate(),
            'years' => $placement->getYears(),
            'final' => $placement->getFinal(),
            'paid' => $placement->getPaid(),
            'interest' => $placement->getInterest(),
            'rent' => $placement->getRent(),
            'monthlyDeposit' => $placement->getMonthlyDeposit(),
        ];
    }

    /**
     * @param list<Placement> $placements
     *
     * @return list<array<string, mixed>>
     */
    public static function list(array $placements): array
    {
        return array_map(self::of(...), $placements);
    }
}
