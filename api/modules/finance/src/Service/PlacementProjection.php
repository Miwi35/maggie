<?php

declare(strict_types=1);

namespace Maggie\Finance\Service;

use Maggie\Finance\Entity\Placement;
use Maggie\Finance\Entity\Project;
use Maggie\Finance\Enum\FundingSourceKind;

/**
 * A placement inside a planning (MAG-556), pure.
 *
 * Its deposits take the capacity, month by month, while it runs. The capital
 * the bought projects free from it (their `capital` sources that name it) leaves
 * it the month each project is bought: the placement is then drawn with and
 * without those withdrawals, `{curveWithout, curveWith, gapAtEnd}` — a
 * simulation, never a promise.
 */
final class PlacementProjection
{
    public function __construct(private readonly PlacementSimulator $simulator)
    {
    }

    /**
     * What the placements' deposits take of the capacity, per month index, over
     * the horizon: the deposits averaged per month, only while a placement runs.
     *
     * @param list<Placement> $placements
     *
     * @return array<int, int>
     */
    public function deposits(array $placements, int $horizon): array
    {
        $deposits = [];
        for ($month = 0; $month < $horizon; ++$month) {
            $sum = 0;
            foreach ($placements as $placement) {
                if ($month < $placement->getYears() * 12) {
                    $sum += $placement->getMonthlyDeposit();
                }
            }
            if ($sum > 0) {
                $deposits[$month] = $sum;
            }
        }

        return $deposits;
    }

    /**
     * The placement with the withdrawals the plan implies.
     *
     * @param array<string, mixed>   $plan     {@see ProjectPlanner::plan()}
     * @param array<string, Project> $projects by id
     *
     * @return array<string, mixed> {@see PlacementView::of()} plus withdrawals, curveWithout, curveWith, gapAtEnd, finalWith, rentWith, withdrawn
     */
    public function figures(Placement $placement, array $plan, array $projects): array
    {
        $withdrawals = [];
        $byMonth = [];
        foreach ($plan['projects'] as $row) {
            $project = $projects[$row['projectId']] ?? null;
            if (null === $project || null === $row['purchaseMonth']) {
                continue;
            }

            $amount = 0;
            foreach ($project->getFundingSources() as $source) {
                if (FundingSourceKind::Capital === $source->getKind() && $source->getPlacementId() === (string) $placement->getId()) {
                    $amount += $source->getAmountCents();
                }
            }
            if ($amount <= 0) {
                continue;
            }

            $index = self::monthIndex($plan['startMonth'], $row['purchaseMonth']);
            $byMonth[$index] = ($byMonth[$index] ?? 0) + $amount;
            $withdrawals[] = [
                'projectId' => $row['projectId'],
                'name' => $row['name'],
                'month' => $row['purchaseMonth'],
                'amountCents' => $amount,
            ];
        }

        $drawn = $this->simulator->withWithdrawals($placement, $byMonth);

        return PlacementView::of($placement) + [
            'withdrawals' => $withdrawals,
            'withdrawn' => $drawn['withdrawn'],
            'curveWithout' => $drawn['curveWithout'],
            'curveWith' => $drawn['curveWith'],
            'gapAtEnd' => $drawn['gapAtEnd'],
            'finalWith' => $drawn['finalWith'],
            'rentWith' => $drawn['rentWith'],
        ];
    }

    private static function monthIndex(string $start, string $month): int
    {
        return ((int) substr($month, 0, 4) - (int) substr($start, 0, 4)) * 12 + (int) substr($month, 5, 2) - (int) substr($start, 5, 2);
    }
}
