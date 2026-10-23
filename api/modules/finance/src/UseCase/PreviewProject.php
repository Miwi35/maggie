<?php

declare(strict_types=1);

namespace Maggie\Finance\UseCase;

use Maggie\Core\Entity\User;
use Maggie\Finance\Entity\Project;
use Maggie\Finance\Enum\ScenarioKind;
use Maggie\Finance\Exception\ScenarioNotFound;
use Maggie\Finance\Repository\ScenarioRepository;
use Maggie\Finance\Service\IndependenceBase;
use Maggie\Finance\Service\PlanningInputs;
use Maggie\Finance\Service\ProjectRules;
use Maggie\Finance\Service\ScenarioCalculator;
use Maggie\Finance\Service\ScenarioCatalog;
use Maggie\Finance\Service\ScenarioSpec;

/**
 * What adding a project would change in a scenario, before anyone adds it
 * (MAG-553, spec rules 7 and 12). The project is built the way a creation
 * builds it but never persisted: nothing is written, the planning is simply run
 * twice, without it and with it last in the scenario's order.
 *
 * The scenario is the current one, none meaning the user's own list in their
 * priority order, or the one named by `$scenarioId` (the project can be added
 * to another scenario than the current one). Facts only, never a verdict.
 */
class PreviewProject
{
    public function __construct(
        private readonly ReadPlanningInputs $readPlanningInputs,
        private readonly MeasureIndependenceBaseline $measureIndependenceBaseline,
        private readonly ScenarioRepository $scenarioRepository,
        private readonly ScenarioCatalog $catalog,
        private readonly ScenarioCalculator $calculator,
        private readonly ProjectRules $rules,
    ) {
    }

    /**
     * @param list<string> $dependsOn ids of the user's projects the new one waits for
     *
     * @return array{
     *     scenario: array{id: string, name: string, editable: bool}|null,
     *     project: array{name: string, amountCents: int, currency: string},
     *     dates: list<array{projectId: string, name: string, before: string|null, after: string|null}>,
     *     nDate: array{before: string|null, after: string|null},
     *     metrics: array{realCost: int, availableAt: string|null, nImpactMonths: int|null}
     * }
     *
     * @throws \DomainException when the project would be refused at creation
     * @throws ScenarioNotFound when `$scenarioId` is not one of the user's scenarios
     */
    public function execute(
        User $user,
        string $name,
        int $amountCents,
        string $currency = 'EUR',
        string $wellbeing = 'medium',
        string $dateMode = 'none',
        ?string $targetOn = null,
        array $dependsOn = [],
        ?string $scenarioId = null,
    ): array {
        $this->rules->assertName($name);
        if (mb_strlen($name) > 255) {
            throw new \DomainException('name must not exceed 255 characters.');
        }
        $this->rules->assertAmount($amountCents);
        $this->rules->assertCurrency($currency);
        $mode = $this->rules->dateMode($dateMode);
        $day = null === $targetOn ? null : Project::dayFromString($targetOn);
        $this->rules->assertDate($mode, $day);
        $dependencies = $this->rules->dependencies($user, null, $dependsOn);

        $draft = (new Project())
            ->setUser($user)
            ->setName($name)
            ->setAmountCents($amountCents)
            ->setCurrency($currency)
            ->setWellbeing($this->rules->wellbeing($wellbeing))
            ->setDateMode($mode)
            ->setTargetOn($day)
            ->setDependsOn($dependencies);

        $inputs = $this->readPlanningInputs->execute($user);
        $base = $this->measureIndependenceBaseline->execute($user, $inputs->start())['base'];

        [$scenario, $spec] = $this->scenarioAndSpec($user, $scenarioId, $inputs, $base);

        $draftId = (string) $draft->getId();
        $withDraft = new PlanningInputs(
            $inputs->today,
            $inputs->netCapacityCents,
            $inputs->isIncomeKnown,
            $inputs->cushion,
            $inputs->reserveCents,
            [...$inputs->projects, $draft],
            $inputs->loans,
            $inputs->placements,
        );

        $before = $this->calculator->evaluate($inputs, $base, $spec);
        $after = $this->calculator->evaluate($withDraft, $base, $spec->withProjectIds([...$spec->projectIds, $draftId]));

        $afterDates = [];
        foreach ($after['schedule'] as $row) {
            $afterDates[$row['projectId']] = $row['purchaseMonth'];
        }

        $dates = [];
        foreach ($before['schedule'] as $row) {
            $dates[] = [
                'projectId' => $row['projectId'],
                'name' => $row['name'],
                'before' => $row['purchaseMonth'],
                'after' => $afterDates[$row['projectId']] ?? null,
            ];
        }

        return [
            'scenario' => $scenario,
            'project' => ['name' => $name, 'amountCents' => $amountCents, 'currency' => $currency],
            'dates' => $dates,
            'nDate' => ['before' => $before['nDate'], 'after' => $after['nDate']],
            'metrics' => [
                // No funding source yet at creation: paid from the salary, so what it costs is what it is worth.
                'realCost' => $amountCents,
                'availableAt' => $afterDates[$draftId] ?? null,
                'nImpactMonths' => self::monthsBetween($before['nDate'], $after['nDate']),
            ],
        ];
    }

    /**
     * @return array{0: array{id: string, name: string, editable: bool}|null, 1: ScenarioSpec}
     */
    private function scenarioAndSpec(User $user, ?string $scenarioId, PlanningInputs $inputs, IndependenceBase $base): array
    {
        if (null !== $scenarioId) {
            $item = $this->catalog->find($user, $scenarioId, $inputs, $base) ?? throw new ScenarioNotFound($scenarioId);

            return [['id' => $item->id, 'name' => $item->name, 'editable' => ScenarioKind::Auto !== $item->kind], $item->spec()];
        }

        $current = $this->scenarioRepository->findCurrent($user);
        if (null === $current) {
            return [null, ScenarioSpec::ofProjects($inputs->projects)];
        }

        return [
            ['id' => (string) $current->getId(), 'name' => $current->getName(), 'editable' => ScenarioKind::Auto !== $current->getKind()],
            ScenarioSpec::fromScenario($current),
        ];
    }

    /** Months from `$before` to `$after` (YYYY-MM), null when N is not reached in either. */
    private static function monthsBetween(?string $before, ?string $after): ?int
    {
        if (null === $before || null === $after) {
            return null;
        }

        return self::monthNumber($after) - self::monthNumber($before);
    }

    private static function monthNumber(string $month): int
    {
        [$year, $number] = array_map('intval', explode('-', $month));

        return $year * 12 + $number;
    }
}
