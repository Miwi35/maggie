<?php

declare(strict_types=1);

namespace Maggie\Finance\Service;

use Maggie\Finance\Enum\CushionState;

/**
 * The events of a scenario's timeline, in the format the frise draws
 * (MAG-550). The format is a contract, written out to
 * `api/contract/timeline-events.json`: changing it makes the contract test
 * fail on purpose, so the screens that read it are told.
 *
 * An event is `{id, month, type, category, label, sublabel, amount_eur,
 * monthly_impact_eur, n_impact_months, detail}`:
 *  - `id` is stable for the same scenario and the same event;
 *  - `month` is YYYY-MM; `amount_eur` is the amount at stake in euros, positive;
 *  - `monthly_impact_eur` is what the event changes in the monthly budget, in
 *    euros: negative when it asks for a payment, positive when it frees one;
 *  - `n_impact_months` is how many months the event moves the date N (negative:
 *    sooner), null when N is not reached or the event does not weigh on it;
 *  - `detail` holds the references (`projectId`, `loanId`…) the screen opens.
 */
final class ScenarioTimeline
{
    /** @var array<string, string> field → type */
    public const FIELDS = [
        'id' => 'string',
        'month' => 'string (YYYY-MM)',
        'type' => 'string',
        'category' => 'string',
        'label' => 'string',
        'sublabel' => 'string|null',
        'amount_eur' => 'number|null',
        'monthly_impact_eur' => 'number|null',
        'n_impact_months' => 'integer|null',
        'detail' => 'object',
    ];

    public const CATEGORIES = ['projet', 'dette', 'investissement', 'indépendance', 'fiscal', 'matelas'];

    /** @var array<string, string> category → colour, the one every channel draws it in (MAG-554) */
    public const COLORS = [
        'projet' => '#B8DE6F',
        'dette' => '#8FB8FF',
        'investissement' => '#F2C46D',
        'indépendance' => '#7FD1AE',
        'fiscal' => '#C7B5FF',
        'matelas' => '#ABA6B8',
    ];

    /** @var array<string, string> category → label the filters show */
    public const LABELS = [
        'projet' => 'Projets',
        'dette' => 'Dettes',
        'investissement' => 'Investissements',
        'indépendance' => 'Indépendance',
        'fiscal' => 'Fiscal',
        'matelas' => 'Matelas',
    ];

    /** @var array<string, string> type → category (`fiscal` has no event yet) */
    public const TYPES = [
        'project_start' => 'projet',
        'project_purchase' => 'projet',
        'debt_end' => 'dette',
        'early_repayment' => 'dette',
        'placement_start' => 'investissement',
        'placement_milestone' => 'investissement',
        'independence_milestone' => 'indépendance',
        'independence_reached' => 'indépendance',
        'cushion_complete' => 'matelas',
        'cushion_recharging' => 'matelas',
    ];

    /**
     * @param array{startMonth: string, cushion: array{state: string, completeMonth: string|null}, months: list<array{cushionCents: int}>, projects: list<array<string, mixed>>} $plan
     * @param array{loans: list<array{loanId: string, endMonth: string|null}>, repayments: list<array<string, mixed>>}                                                           $effects
     * @param array{milestones: array<int, string|null>}                                                                                                                         $independence
     * @param array<string, int|null>                                                                                                                                            $projectImpacts   projectId → months N moves because of the project
     * @param array<string, int|null>                                                                                                                                            $repaymentImpacts repayment id → idem
     * @param array<string, string>                                                                                                                                              $loanNames
     * @param list<array<string, mixed>>                                                                                                                                         $placements       PlacementView rows
     *
     * @return list<array{id: string, month: string, type: string, category: string, label: string, sublabel: string|null, amount_eur: float|null, monthly_impact_eur: float|null, n_impact_months: int|null, detail: array<string, mixed>}>
     */
    public function build(array $plan, array $effects, array $independence, array $projectImpacts, array $repaymentImpacts, array $loanNames, array $placements): array
    {
        $events = [];

        foreach ($plan['projects'] as $project) {
            $id = $project['projectId'];
            $purchase = $project['purchaseMonth'];
            if (null === $purchase) {
                continue;
            }

            if ($project['savingFromMonth'] < $purchase) {
                $events[] = self::event("project_start:{$id}", $project['savingFromMonth'], 'project_start', $project['name'], 'Début de l\'épargne', $project['amountCents'], null, null, ['projectId' => $id]);
            }

            $loan = $project['loan'];
            $events[] = self::event(
                "project_purchase:{$id}",
                $purchase,
                'project_purchase',
                $project['name'],
                null === $loan ? 'Achat' : 'Achat financé par un prêt',
                $project['amountCents'],
                null === $loan ? null : -$loan['monthlyPaymentCents'],
                $projectImpacts[$id] ?? null,
                ['projectId' => $id, 'wellbeing' => $project['wellbeing'], 'loanStartMonth' => $loan['startMonth'] ?? null],
            );

            if (null !== $loan && null !== $loan['endMonth']) {
                $events[] = self::event("debt_end:project:{$id}", $loan['endMonth'], 'debt_end', 'Fin du prêt : '.$project['name'], null, null, $loan['monthlyPaymentCents'], null, ['projectId' => $id]);
            }
        }

        foreach ($effects['loans'] as $loan) {
            if (null !== $loan['endMonth']) {
                $name = $loanNames[$loan['loanId']] ?? 'prêt';
                $events[] = self::event("debt_end:loan:{$loan['loanId']}", $loan['endMonth'], 'debt_end', 'Fin du prêt : '.$name, null, null, null, null, ['loanId' => $loan['loanId']]);
            }
        }

        foreach ($effects['repayments'] as $repayment) {
            $name = $loanNames[$repayment['loanId']] ?? 'prêt';
            $events[] = self::event(
                "early_repayment:{$repayment['id']}",
                $repayment['month'],
                'early_repayment',
                'Remboursement anticipé : '.$name,
                0 === $repayment['penaltyCents'] ? null : 'Indemnité : '.self::euros($repayment['penaltyCents']).' €',
                $repayment['amountCents'],
                $repayment['freedMonthlyCents'],
                $repaymentImpacts[$repayment['id']] ?? null,
                ['loanId' => $repayment['loanId'], 'repaymentId' => $repayment['id'], 'penaltyCents' => $repayment['penaltyCents'], 'interestSavedCents' => $repayment['interestSavedCents']],
            );
        }

        foreach ($placements as $placement) {
            $events[] = self::event(
                "placement_start:{$placement['id']}",
                $plan['startMonth'],
                'placement_start',
                $placement['name'],
                null,
                $placement['initial'],
                -$placement['monthlyDeposit'],
                null,
                ['placementId' => $placement['id'], 'finalCents' => $placement['final'], 'rentCents' => $placement['rent']],
            );
        }

        $start = new \DateTimeImmutable($plan['startMonth'].'-01');
        foreach ($placements as $placement) {
            $offset = $placement['years'] * 12;
            if ($offset < 1 || $offset >= IndependenceProjector::HORIZON_MONTHS) {
                continue;
            }
            $events[] = self::event(
                "placement_milestone:{$placement['id']}",
                $start->modify(\sprintf('+%d months', $offset))->format('Y-m'),
                'placement_milestone',
                $placement['name'],
                'Terme du placement simulé',
                $placement['final'],
                $placement['rent'],
                null,
                ['placementId' => $placement['id'], 'years' => $placement['years']],
            );
        }

        foreach ($independence['milestones'] as $percent => $month) {
            if (null === $month) {
                continue;
            }
            $events[] = 100 === $percent
                ? self::event('independence_reached', $month, 'independence_reached', 'Indépendance financière', 'Les rentes couvrent le train de vie', null, null, null, ['percent' => 100])
                : self::event("independence_milestone:{$percent}", $month, 'independence_milestone', "{$percent} % du train de vie couvert", null, null, null, null, ['percent' => $percent]);
        }

        if (null !== $plan['cushion']['completeMonth']) {
            $events[] = self::event('cushion_complete', $plan['cushion']['completeMonth'], 'cushion_complete', 'Matelas de sécurité constitué', null, null, null, null, []);
        }

        if (CushionState::Recharging->value === $plan['cushion']['state']) {
            $months = array_column($plan['months'], 'cushionCents');
            $events[] = self::event(
                'cushion_recharging',
                $plan['startMonth'],
                'cushion_recharging',
                'Recharge du matelas en cours',
                null === $plan['cushion']['completeMonth'] ? null : 'Matelas reconstitué en '.$plan['cushion']['completeMonth'],
                null,
                -($months[0] ?? 0),
                null,
                [],
            );
        }

        usort($events, static fn (array $a, array $b): int => [$a['month'], $a['id']] <=> [$b['month'], $b['id']]);

        return $events;
    }

    /**
     * @param array<string, mixed> $detail
     *
     * @return array{id: string, month: string, type: string, category: string, label: string, sublabel: string|null, amount_eur: float|null, monthly_impact_eur: float|null, n_impact_months: int|null, detail: array<string, mixed>}
     */
    private static function event(string $id, string $month, string $type, string $label, ?string $sublabel, ?int $amountCents, ?int $monthlyCents, ?int $nImpact, array $detail): array
    {
        return [
            'id' => $id,
            'month' => $month,
            'type' => $type,
            'category' => self::TYPES[$type],
            'label' => $label,
            'sublabel' => $sublabel,
            'amount_eur' => null === $amountCents ? null : $amountCents / 100,
            'monthly_impact_eur' => null === $monthlyCents ? null : $monthlyCents / 100,
            'n_impact_months' => $nImpact,
            'detail' => $detail,
        ];
    }

    private static function euros(int $cents): string
    {
        return number_format($cents / 100, 2, ',', ' ');
    }
}
