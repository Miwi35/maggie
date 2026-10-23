<?php

declare(strict_types=1);

namespace Maggie\Finance\Service;

/**
 * A scenario as the API and Maggie read it (MAG-550): what it is, and what it
 * gives — the date N and the metrics, computed on every read, never stored.
 *
 * `scenario = { id, name, kind, status, current, projects: [{projectId,
 * position}], repayments, placements: [{placementId}], monthlySaving, nDate,
 * metrics: {projectsDoneBy, debtsEndDate, interestPaid, wellbeing} }`; the
 * detail adds the curve, the milestones, the schedule, the effect of each
 * repayment, the placements' figures and the timeline events.
 */
final class ScenarioView
{
    public function __construct(private readonly ScenarioCalculator $calculator)
    {
    }

    /** @return array<string, mixed> */
    public function summary(ScenarioItem $item, PlanningInputs $inputs, IndependenceBase $base): array
    {
        return $this->describe($item, $this->calculator->evaluate($inputs, $base, $item->spec()));
    }

    /** @return array<string, mixed> */
    public function detail(ScenarioItem $item, PlanningInputs $inputs, IndependenceBase $base, bool $withEvents = true): array
    {
        $result = $this->calculator->evaluate($inputs, $base, $item->spec(), $withEvents);

        return $this->describe($item, $result) + [
            'curve' => $result['curve'],
            'milestones' => $result['milestones'],
            'schedule' => $result['schedule'],
            'repaymentEffects' => $result['repayments'],
            'placementFigures' => $result['placements'],
        ] + ($withEvents ? ['events' => $result['events']] : []);
    }

    /**
     * @param array<string, mixed> $result
     *
     * @return array<string, mixed>
     */
    private function describe(ScenarioItem $item, array $result): array
    {
        $metrics = $result['metrics'];
        unset($metrics['nDate']);

        return [
            'id' => $item->id,
            'name' => $item->name,
            'kind' => $item->kind->value,
            'status' => $item->status->value,
            'current' => $item->current,
            'projects' => array_map(
                static fn (string $projectId, int $position): array => ['projectId' => $projectId, 'position' => $position],
                $item->projectIds,
                array_keys($item->projectIds),
            ),
            'repayments' => $item->repayments,
            'placements' => array_map(static fn (string $placementId): array => ['placementId' => $placementId], $item->placementIds),
            'monthlySaving' => $item->monthlySaving,
            'nDate' => $result['nDate'],
            'metrics' => $metrics,
        ];
    }
}
