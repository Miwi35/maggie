<?php

declare(strict_types=1);

namespace Maggie\Finance\UseCase;

use Maggie\Core\Entity\User;
use Maggie\Finance\Exception\ScenarioNotFound;
use Maggie\Finance\Service\NDifference;
use Maggie\Finance\Service\ScenarioCatalog;
use Maggie\Finance\Service\ScenarioItem;
use Maggie\Finance\Service\ScenarioView;

/**
 * Reads the user's scenarios (MAG-550): the list with their date N and
 * metrics, one in detail, and several side by side. Facts only — no scenario
 * is called better, none recommended.
 */
class GetScenarios
{
    public const MAX_COMPARED = 6;

    public function __construct(
        private readonly ReadPlanningInputs $readPlanningInputs,
        private readonly MeasureIndependenceBaseline $measureIndependenceBaseline,
        private readonly ScenarioCatalog $catalog,
        private readonly ScenarioView $view,
    ) {
    }

    /** @return array{scenarios: list<array<string, mixed>>} */
    public function list(User $user): array
    {
        $inputs = $this->readPlanningInputs->execute($user);
        $base = $this->measureIndependenceBaseline->execute($user, $inputs->start())['base'];

        return ['scenarios' => array_map(
            fn (ScenarioItem $item): array => $this->view->summary($item, $inputs, $base),
            $this->catalog->all($user, $inputs, $base),
        )];
    }

    /** @return array<string, mixed>|null */
    public function one(User $user, string $id, bool $withEvents = true): ?array
    {
        $inputs = $this->readPlanningInputs->execute($user);
        $base = $this->measureIndependenceBaseline->execute($user, $inputs->start())['base'];

        $item = $this->catalog->find($user, $id, $inputs, $base);

        return null === $item ? null : $this->view->detail($item, $inputs, $base, $withEvents);
    }

    /**
     * The scenarios' curves and metrics, and each one's date N against the
     * first (the reference): « N 9 mois plus tôt que « A » ».
     *
     * @param list<string> $ids at least two, the first is the reference
     *
     * @return array<string, mixed>
     *
     * @throws \DomainException when fewer than two ids or a duplicate
     * @throws ScenarioNotFound when a scenario is not the user's
     */
    public function compare(User $user, array $ids): array
    {
        if (\count($ids) < 2) {
            throw new \DomainException('Compare at least two scenarios: give their ids, the first is the reference.');
        }
        if (\count(array_unique($ids)) !== \count($ids)) {
            throw new \DomainException('A scenario is named more than once.');
        }
        if (\count($ids) > self::MAX_COMPARED) {
            throw new \DomainException(\sprintf('Compare %d scenarios at most.', self::MAX_COMPARED));
        }

        $inputs = $this->readPlanningInputs->execute($user);
        $base = $this->measureIndependenceBaseline->execute($user, $inputs->start())['base'];

        $scenarios = [];
        foreach ($ids as $id) {
            $item = $this->catalog->find($user, $id, $inputs, $base) ?? throw new ScenarioNotFound($id);
            $detail = $this->view->detail($item, $inputs, $base, false);
            unset($detail['schedule'], $detail['repaymentEffects'], $detail['placementFigures']);
            $scenarios[] = $detail;
        }

        $reference = $scenarios[0];
        $differences = [];
        foreach (\array_slice($scenarios, 1) as $scenario) {
            $differences[] = ['scenarioId' => $scenario['id'], 'againstId' => $reference['id']] + NDifference::between($scenario, $reference);
        }

        return ['referenceId' => $reference['id'], 'scenarios' => $scenarios, 'differences' => $differences];
    }
}
