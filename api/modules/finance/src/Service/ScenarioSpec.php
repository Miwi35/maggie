<?php

declare(strict_types=1);

namespace Maggie\Finance\Service;

use Maggie\Finance\Entity\Project;
use Maggie\Finance\Entity\Scenario;

/**
 * What a scenario asks the planning for (MAG-550): the projects it keeps, in
 * its order, a monthly saving that replaces the measured capacity, the early
 * repayments it simulates and the placements it refers to. The projects
 * themselves are never touched.
 */
final readonly class ScenarioSpec
{
    /**
     * @param list<string>               $projectIds   in the scenario's priority order
     * @param list<array<string, mixed>> $repayments   normalized by {@see ScenarioRepayments::normalize()}
     * @param list<string>               $placementIds
     */
    public function __construct(
        public array $projectIds,
        public ?int $monthlySaving = null,
        public array $repayments = [],
        public array $placementIds = [],
    ) {
    }

    /** @param list<Project> $projects in priority order */
    public static function ofProjects(array $projects): self
    {
        return new self(array_map(static fn (Project $project): string => (string) $project->getId(), $projects));
    }

    public static function fromScenario(Scenario $scenario): self
    {
        $projects = $scenario->getProjects();
        usort($projects, static fn (array $a, array $b): int => $a['position'] <=> $b['position']);

        return new self(
            array_map(static fn (array $row): string => $row['projectId'], $projects),
            $scenario->getMonthlySaving(),
            $scenario->getRepayments(),
            array_map(static fn (array $row): string => $row['placementId'], $scenario->getPlacements()),
        );
    }

    /** @param list<string> $projectIds */
    public function withProjectIds(array $projectIds): self
    {
        return new self($projectIds, $this->monthlySaving, $this->repayments, $this->placementIds);
    }

    public function withoutProject(string $projectId): self
    {
        return $this->withProjectIds(array_values(array_diff($this->projectIds, [$projectId])));
    }

    public function withoutRepayment(string $repaymentId): self
    {
        return new self(
            $this->projectIds,
            $this->monthlySaving,
            array_values(array_filter($this->repayments, static fn (array $row): bool => ($row['id'] ?? null) !== $repaymentId)),
            $this->placementIds,
        );
    }
}
