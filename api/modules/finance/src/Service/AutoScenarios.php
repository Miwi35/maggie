<?php

declare(strict_types=1);

namespace Maggie\Finance\Service;

use Maggie\Finance\Entity\Project;
use Maggie\Finance\Enum\ProjectWellbeing;

/**
 * The three scenarios Maggie lays out on her own from the user's projects
 * (MAG-550, spec rule 5). Each is only an order of the projects that already
 * exist; none of them is the current one until the user says so.
 *
 * What a project weighs on N is how much later N comes with the project placed
 * last than without it. From it:
 *  - **rapid**: N as early as the orders tried allow — the projects that weigh
 *    least first — and the Comfort projects that push N back by more than
 *    {@see self::COMFORT_DEFERRAL_MONTHS} months come last;
 *  - **balanced**: N as early as possible while the Essential and High
 *    projects keep the front, in the user's order;
 *  - **quality**: the projects that count most for wellbeing first.
 *
 * Ties keep the user's own order.
 */
final class AutoScenarios
{
    public const RAPID = 'rapid';
    public const BALANCED = 'balanced';
    public const QUALITY = 'quality';

    /** @var array<string, string> key → name */
    public const NAMES = [
        self::RAPID => 'Rapide',
        self::BALANCED => 'Équilibré',
        self::QUALITY => 'Qualité de vie',
    ];

    /** A Comfort project that delays N by more than this is put off. */
    public const COMFORT_DEFERRAL_MONTHS = 3;

    public function __construct(private readonly ScenarioCalculator $calculator)
    {
    }

    /** @return array<string, ScenarioSpec> by key */
    public function specs(PlanningInputs $inputs, IndependenceBase $base): array
    {
        $projects = $inputs->projects;
        $ids = array_map(static fn (Project $project): string => (string) $project->getId(), $projects);
        if ([] === $ids) {
            return array_fill_keys(array_keys(self::NAMES), new ScenarioSpec([]));
        }

        $months = fn (array $order): int => $this->calculator->monthsToN($inputs, $base, new ScenarioSpec($order));
        $impacts = $this->impacts($ids, $months);

        $byId = [];
        foreach ($projects as $i => $project) {
            $byId[(string) $project->getId()] = ['position' => $i, 'wellbeing' => $project->getWellbeing()];
        }
        $byImpact = static function (array $order) use ($impacts, $byId): array {
            usort($order, static fn (string $a, string $b): int => [$impacts[$a], $byId[$a]['position']] <=> [$impacts[$b], $byId[$b]['position']]);

            return $order;
        };

        $deferred = static function (array $order) use ($impacts, $byId): array {
            $kept = [];
            $late = [];
            foreach ($order as $id) {
                if (ProjectWellbeing::Comfort === $byId[$id]['wellbeing'] && $impacts[$id] > self::COMFORT_DEFERRAL_MONTHS) {
                    $late[] = $id;
                } else {
                    $kept[] = $id;
                }
            }

            return [...$kept, ...$late];
        };

        $protected = array_values(array_filter($ids, static fn (string $id): bool => \in_array($byId[$id]['wellbeing'], [ProjectWellbeing::Essential, ProjectWellbeing::High], true)));
        $others = array_values(array_diff($ids, $protected));

        $quality = $ids;
        usort($quality, static fn (string $a, string $b): int => [-$byId[$a]['wellbeing']->score(), $byId[$a]['position']] <=> [-$byId[$b]['wellbeing']->score(), $byId[$b]['position']]);

        return [
            self::RAPID => new ScenarioSpec($this->earliest([$deferred($byImpact($ids)), $deferred($ids)], $months)),
            self::BALANCED => new ScenarioSpec($this->earliest([[...$protected, ...$byImpact($others)], [...$protected, ...$others]], $months)),
            self::QUALITY => new ScenarioSpec($quality),
        ];
    }

    /**
     * The months each project delays N when it comes last.
     *
     * @param list<string>                $ids
     * @param callable(list<string>): int $months
     *
     * @return array<string, int>
     */
    private function impacts(array $ids, callable $months): array
    {
        $impacts = [];
        foreach ($ids as $id) {
            $others = array_values(array_diff($ids, [$id]));
            $impacts[$id] = $months([...$others, $id]) - $months($others);
        }

        return $impacts;
    }

    /**
     * The first of the orders with the earliest N.
     *
     * @param list<list<string>>          $orders
     * @param callable(list<string>): int $months
     *
     * @return list<string>
     */
    private function earliest(array $orders, callable $months): array
    {
        $best = $orders[0];
        $bestMonths = $months($best);
        foreach (\array_slice($orders, 1) as $order) {
            $candidate = $months($order);
            if ($candidate < $bestMonths) {
                [$best, $bestMonths] = [$order, $candidate];
            }
        }

        return $best;
    }
}
