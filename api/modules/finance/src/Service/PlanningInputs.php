<?php

declare(strict_types=1);

namespace Maggie\Finance\Service;

use Maggie\Finance\Entity\Loan;
use Maggie\Finance\Entity\Placement;
use Maggie\Finance\Entity\Project;

/**
 * What the planning reads of the user's world (MAG-526), read once so a
 * scenario can be computed many times from it (MAG-550).
 */
final readonly class PlanningInputs
{
    /**
     * @param array{state: string, deficitCents: int, monthlyRechargeCents: int} $cushion
     * @param list<Project>                                                      $projects   in priority order
     * @param list<Loan>                                                         $loans      the signed ones
     * @param list<Placement>                                                    $placements
     */
    public function __construct(
        public \DateTimeImmutable $today,
        public int $netCapacityCents,
        public bool $isIncomeKnown,
        public array $cushion,
        public int $reserveCents,
        public array $projects,
        public array $loans = [],
        public array $placements = [],
    ) {
    }

    /** @return array<string, Project> */
    public function projectsById(): array
    {
        $byId = [];
        foreach ($this->projects as $project) {
            $byId[(string) $project->getId()] = $project;
        }

        return $byId;
    }

    /** @return array<string, Placement> */
    public function placementsById(): array
    {
        $byId = [];
        foreach ($this->placements as $placement) {
            $byId[(string) $placement->getId()] = $placement;
        }

        return $byId;
    }

    /** The first day of the current month, M0. */
    public function start(): \DateTimeImmutable
    {
        return $this->today->modify('first day of this month')->setTime(0, 0);
    }
}
