<?php

declare(strict_types=1);

namespace Maggie\Finance\UseCase;

use Maggie\Core\Entity\User;
use Maggie\Finance\Exception\ScenarioNotFound;
use Maggie\Finance\Repository\ScenarioRepository;
use Maggie\Finance\Service\IndependenceBase;
use Maggie\Finance\Service\IndependenceProjector;
use Maggie\Finance\Service\NDifference;
use Maggie\Finance\Service\PlanningInputs;
use Maggie\Finance\Service\ScenarioCalculator;
use Maggie\Finance\Service\ScenarioCatalog;
use Maggie\Finance\Service\ScenarioItem;
use Maggie\Finance\Service\ScenarioSpec;
use Maggie\Finance\Service\ScenarioTimeline;

/**
 * The 20-year frise (MAG-554, rule 10): the events of the current scenario
 * month by month up to independence, and — when asked — those of a second
 * scenario beside them, with the gap of N in facts. Recomputed on every read.
 *
 * With no current scenario the frise draws the planning the Dashboard shows:
 * every project in the user's order (`scenario` is then null). The compared
 * scenario is any scenario of the catalog, automatic ones included; it cannot
 * be the one it is compared with.
 */
class GetFinanceTimeline
{
    public const ASSUMPTIONS = 'Hypothèses : matelas intact, revenus stables, sans vacance locative ni imprévu';

    public function __construct(
        private readonly ReadPlanningInputs $readPlanningInputs,
        private readonly MeasureIndependenceBaseline $measureIndependenceBaseline,
        private readonly ScenarioRepository $scenarioRepository,
        private readonly ScenarioCatalog $catalog,
        private readonly ScenarioCalculator $calculator,
    ) {
    }

    /**
     * @param string|null $compareId a scenario id (stored, or `auto-<key>`)
     *
     * @return array<string, mixed>
     *
     * @throws ScenarioNotFound when the compared scenario is not the user's
     * @throws \DomainException when it is the current one
     */
    public function execute(User $user, ?string $compareId = null): array
    {
        $inputs = $this->readPlanningInputs->execute($user);
        $base = $this->measureIndependenceBaseline->execute($user, $inputs->start())['base'];

        $stored = $this->scenarioRepository->findCurrent($user);
        $current = null === $stored ? null : ScenarioItem::stored($stored);

        $compared = null;
        if (null !== $compareId) {
            $compared = $this->catalog->find($user, $compareId, $inputs, $base) ?? throw new ScenarioNotFound($compareId);
            if (null !== $current && $compared->id === $current->id) {
                throw new \DomainException('The current scenario is already drawn: compare it with another one.');
            }
        }

        $currentLine = $this->line($inputs, $base, $current, null === $current ? ScenarioSpec::ofProjects($inputs->projects) : $current->spec());
        $comparedLine = null === $compared ? null : $this->line($inputs, $base, $compared, $compared->spec());

        return [
            'startMonth' => $inputs->start()->format('Y-m'),
            'horizonMonths' => IndependenceProjector::HORIZON_MONTHS,
            'assumptions' => self::ASSUMPTIONS,
            'categories' => array_map(
                static fn (string $key): array => ['key' => $key, 'label' => ScenarioTimeline::LABELS[$key], 'color' => ScenarioTimeline::COLORS[$key]],
                ScenarioTimeline::CATEGORIES,
            ),
            'current' => $currentLine,
            'compared' => $comparedLine,
            'difference' => null === $comparedLine ? null : NDifference::between(
                ['name' => $comparedLine['name'], 'nDate' => $comparedLine['nDate']],
                ['name' => $currentLine['name'], 'nDate' => $currentLine['nDate']],
            ),
        ];
    }

    /**
     * @return array{scenarioId: string|null, name: string, nDate: string|null, events: list<array<string, mixed>>}
     */
    private function line(PlanningInputs $inputs, IndependenceBase $base, ?ScenarioItem $item, ScenarioSpec $spec): array
    {
        $result = $this->calculator->evaluate($inputs, $base, $spec, true);

        $last = $inputs->start()->modify(\sprintf('+%d months', IndependenceProjector::HORIZON_MONTHS - 1))->format('Y-m');
        $events = array_values(array_filter(
            $result['events'] ?? [],
            static fn (array $event): bool => $event['month'] <= $last,
        ));

        return [
            'scenarioId' => $item?->id,
            'name' => $item->name ?? 'Planning sans scénario',
            'nDate' => $result['nDate'],
            'events' => $events,
        ];
    }
}
