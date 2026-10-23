<?php

declare(strict_types=1);

namespace Maggie\Finance\Service;

use Maggie\Finance\Entity\Placement;
use Maggie\Finance\Enum\ProjectWellbeing;

/**
 * Runs the planning for a scenario and measures what it comes to (MAG-550).
 * Pure: everything is handed in, so the same inputs and the same scenario
 * always give the same figures.
 *
 * The scenario's projects replace the user's list (their order is the
 * priority), its `monthlySaving` replaces the measured capacity, its early
 * repayments weigh on and free the capacity ({@see ScenarioRepayments}).
 * Its placements' deposits take the capacity too, and the capital its projects
 * free from them leaves them when each project is bought (MAG-556): carried with
 * their curve with and without those withdrawals. They do not feed the date N yet.
 */
final class ScenarioCalculator
{
    public function __construct(
        private readonly ProjectPlanner $planner,
        private readonly ScenarioRepayments $repayments,
        private readonly PlanIndependence $independence,
        private readonly ScenarioTimeline $timeline,
        private readonly PlacementProjection $placementProjection,
    ) {
    }

    /**
     * The planning a scenario implies, in the shape of {@see ProjectPlanner::plan()}.
     *
     * @return array{plan: array<string, mixed>, effects: array<string, mixed>}
     */
    public function plan(PlanningInputs $inputs, ScenarioSpec $spec, int $overspentCents = 0, bool $allLoans = false): array
    {
        $effects = $this->repayments->apply($inputs->loans, $spec->repayments, $inputs->start(), $allLoans, false);

        $byId = $inputs->projectsById();
        $projects = [];
        foreach ($spec->projectIds as $id) {
            if (isset($byId[$id])) {
                $projects[] = $byId[$id];
            }
        }

        $plan = $this->planner->plan(
            $inputs->today,
            $spec->monthlySaving ?? $inputs->netCapacityCents,
            null !== $spec->monthlySaving || $inputs->isIncomeKnown,
            $inputs->cushion,
            $inputs->reserveCents,
            $projects,
            $overspentCents,
            $effects['freed'],
            $effects['outlay'],
            $this->placementProjection->deposits($this->placementsOf($inputs, $spec), ProjectPlanner::HORIZON_MONTHS),
        );

        return ['plan' => $plan, 'effects' => $effects];
    }

    /**
     * The date N, the coverage curve and the milestones of a scenario.
     *
     * @return array{startMonth: string, nDate: string|null, curve: list<array{month: string, coverage: float}>, milestones: array<int, string|null>}
     */
    public function independence(PlanningInputs $inputs, IndependenceBase $base, ScenarioSpec $spec): array
    {
        return $this->independenceOf($this->plan($inputs, $spec)['plan'], $base);
    }

    /** Months from now to N for this scenario, the horizon when it is never reached. */
    public function monthsToN(PlanningInputs $inputs, IndependenceBase $base, ScenarioSpec $spec): int
    {
        return PlanIndependence::monthsToN($this->independence($inputs, $base, $spec));
    }

    /**
     * Everything a scenario comes to.
     *
     * `events` (the frise) costs one more computation per project and per
     * repayment to weigh each on N: asked only when it is drawn. It also gives
     * each repayment its `nImpactMonths` (negative: N comes sooner; null when N
     * is not reached with or without it).
     *
     * @return array{
     *     nDate: string|null,
     *     curve: list<array{month: string, coverage: float}>,
     *     milestones: array<int, string|null>,
     *     metrics: array{projectsDoneBy: string|null, debtsEndDate: string|null, interestPaid: int, wellbeing: int, nDate: string|null},
     *     schedule: list<array{projectId: string, name: string, savingFromMonth: string, purchaseMonth: string|null, isBeyondHorizon: bool}>,
     *     repayments: list<array<string, mixed>>,
     *     placements: list<array<string, mixed>>,
     *     events: list<array<string, mixed>>|null
     * }
     */
    public function evaluate(PlanningInputs $inputs, IndependenceBase $base, ScenarioSpec $spec, bool $withEvents = false): array
    {
        ['plan' => $plan, 'effects' => $effects] = $this->plan($inputs, $spec, 0, true);
        $independence = $this->independenceOf($plan, $base);

        $projectsById = $inputs->projectsById();
        $placements = array_map(
            fn (Placement $placement): array => $this->placementProjection->figures($placement, $plan, $projectsById),
            $this->placementsOf($inputs, $spec),
        );

        $events = null;
        $repayments = $effects['repayments'];
        if ($withEvents) {
            $impacts = $this->repaymentImpacts($inputs, $base, $spec, $effects, $independence);
            $repayments = array_map(static fn (array $row): array => $row + ['nImpactMonths' => $impacts[$row['id']] ?? null], $repayments);
            $events = $this->events($inputs, $base, $spec, $plan, $effects, $independence, $placements, $impacts);
        }

        return [
            'nDate' => $independence['nDate'],
            'curve' => $independence['curve'],
            'milestones' => $independence['milestones'],
            'metrics' => $this->metrics($plan, $effects, $independence),
            'schedule' => array_map(static fn (array $row): array => [
                'projectId' => $row['projectId'],
                'name' => $row['name'],
                'savingFromMonth' => $row['savingFromMonth'],
                'purchaseMonth' => $row['purchaseMonth'],
                'isBeyondHorizon' => $row['isBeyondHorizon'],
            ], $plan['projects']),
            'repayments' => $repayments,
            'placements' => $placements,
            'events' => $events,
        ];
    }

    /** @return list<Placement> the scenario's placements, in its order */
    private function placementsOf(PlanningInputs $inputs, ScenarioSpec $spec): array
    {
        $byId = $inputs->placementsById();
        $placements = [];
        foreach ($spec->placementIds as $id) {
            if (isset($byId[$id])) {
                $placements[] = $byId[$id];
            }
        }

        return $placements;
    }

    /**
     * @param array<string, mixed> $plan
     *
     * @return array{startMonth: string, nDate: string|null, curve: list<array{month: string, coverage: float}>, milestones: array<int, string|null>}
     */
    private function independenceOf(array $plan, IndependenceBase $base): array
    {
        return $this->independence->project($plan, $base->passiveIncomeCents, $base->lifestyleCents);
    }

    /**
     * @param array<string, mixed> $plan
     * @param array<string, mixed> $effects
     * @param array<string, mixed> $independence
     *
     * @return array{projectsDoneBy: string|null, debtsEndDate: string|null, interestPaid: int, wellbeing: int, nDate: string|null}
     */
    private function metrics(array $plan, array $effects, array $independence): array
    {
        $done = [];
        $allDone = [] !== $plan['projects'];
        $wellbeing = 0;
        $debtEnds = [];

        foreach ($plan['projects'] as $project) {
            if (null === $project['purchaseMonth']) {
                $allDone = false;
                continue;
            }
            $done[] = $project['purchaseMonth'];
            $wellbeing += ProjectWellbeing::from($project['wellbeing'])->score();
            if (null !== ($project['loan']['endMonth'] ?? null)) {
                $debtEnds[] = $project['loan']['endMonth'];
            }
        }
        foreach ($effects['loans'] as $loan) {
            if (null !== $loan['endMonth']) {
                $debtEnds[] = $loan['endMonth'];
            }
        }

        return [
            'projectsDoneBy' => $allDone ? max($done) : null,
            'debtsEndDate' => [] === $debtEnds ? null : max($debtEnds),
            'interestPaid' => $effects['interestPaidCents'],
            'wellbeing' => $wellbeing,
            'nDate' => $independence['nDate'],
        ];
    }

    /**
     * @param array<string, mixed>       $plan
     * @param array<string, mixed>       $effects
     * @param array<string, mixed>       $independence
     * @param list<array<string, mixed>> $placements
     * @param array<string, int|null>    $repaymentImpacts
     *
     * @return list<array<string, mixed>>
     */
    private function events(PlanningInputs $inputs, IndependenceBase $base, ScenarioSpec $spec, array $plan, array $effects, array $independence, array $placements, array $repaymentImpacts): array
    {
        $own = PlanIndependence::monthsToN($independence);
        $nullIfUnreached = static fn (int $without) => IndependenceProjector::HORIZON_MONTHS === $own && IndependenceProjector::HORIZON_MONTHS === $without ? null : $own - $without;

        $projectImpacts = [];
        foreach ($plan['projects'] as $row) {
            if (null !== $row['purchaseMonth']) {
                $projectImpacts[$row['projectId']] = $nullIfUnreached($this->monthsToN($inputs, $base, $spec->withoutProject($row['projectId'])));
            }
        }
        $loanNames = [];
        foreach ($inputs->loans as $loan) {
            $loanNames[(string) $loan->getId()] = $loan->getName();
        }

        return $this->timeline->build($plan, $effects, $independence, $projectImpacts, $repaymentImpacts, $loanNames, $placements);
    }

    /**
     * @param array<string, mixed> $effects
     * @param array<string, mixed> $independence
     *
     * @return array<string, int|null> repayment id → months N moves because of it
     */
    private function repaymentImpacts(PlanningInputs $inputs, IndependenceBase $base, ScenarioSpec $spec, array $effects, array $independence): array
    {
        $own = PlanIndependence::monthsToN($independence);
        $impacts = [];
        foreach ($effects['repayments'] as $repayment) {
            $without = $this->monthsToN($inputs, $base, $spec->withoutRepayment($repayment['id']));
            $impacts[$repayment['id']] = IndependenceProjector::HORIZON_MONTHS === $own && IndependenceProjector::HORIZON_MONTHS === $without ? null : $own - $without;
        }

        return $impacts;
    }
}
