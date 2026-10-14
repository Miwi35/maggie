<?php

declare(strict_types=1);

namespace Maggie\Finance\UseCase;

use Maggie\Core\Entity\User;
use Maggie\Finance\Entity\Category;
use Maggie\Finance\Entity\Envelope;
use Maggie\Finance\Enum\BudgetMode;
use Maggie\Finance\Enum\TransactionStatus;
use Maggie\Finance\Repository\CategoryRepository;
use Maggie\Finance\Repository\EnvelopeRepository;
use Maggie\Finance\Repository\TransactionRepository;
use Maggie\Finance\Service\EnvelopeNesting;
use Symfony\Component\Uid\Ulid;

/**
 * Budget consumption of a period: every envelope covering it, with what the
 * transactions of its category and of every category below it weigh on it.
 *
 * Envelopes nest like their categories (Loisirs 300 € holds Obsydian 100 €),
 * and none is compulsory at any level: a spend counts in the envelope of its
 * category and in those of all its ancestors, whichever of them have one. The
 * totals only add the root envelopes — those with no envelope above them —
 * so a spend is never counted twice.
 *
 * Only the monthly envelopes make the month's budget (`budgets` and the
 * totals). The annual ones (a calendar year) and the one-off ones (an event,
 * Hellfest 2026) are reported apart, in `outsideMonthlyBudget`. A spend
 * attributed by hand to a one-off envelope keeps its category but counts in
 * that envelope only, out of the monthly and annual ones.
 *
 * The status decides the weight — spent and committed are money already out
 * (consumed), planned is set aside (reserved), to-arbitrate counts for nothing
 * but is reported so its impact can be weighed before deciding.
 */
class GetBudgetStatus
{
    public function __construct(
        private readonly EnvelopeRepository $envelopeRepository,
        private readonly CategoryRepository $categoryRepository,
        private readonly TransactionRepository $transactionRepository,
        private readonly EnvelopeNesting $nesting,
    ) {
    }

    /** @return array<string, mixed> */
    public function execute(User $user, int $year, int $month): array
    {
        $monthly = [];
        $annual = [];
        foreach ($this->envelopeRepository->findCoveringPeriod($user, $year, $month) as $envelope) {
            if (BudgetMode::Monthly === $envelope->getMode()) {
                $monthly[] = $envelope;
            } elseif (BudgetMode::Annual === $envelope->getMode()) {
                $annual[] = $envelope;
            }
        }

        $childrenByParent = [];
        foreach ($this->categoryRepository->findByUser($user) as $category) {
            if (null !== $category->getParent()) {
                $childrenByParent[(string) $category->getParent()->getId()][] = $category->getId();
            }
        }

        $budgets = $this->categoryLines($user, $monthly, $childrenByParent);

        $totalBudgeted = 0;
        $totalSpent = 0;
        $totalCommitted = 0;
        $totalPlanned = 0;
        foreach ($budgets as $line) {
            if (null === $line['parentEnvelopeId']) {
                $totalBudgeted += $line['amountCents'];
                $totalSpent += $line['spentCents'];
                $totalCommitted += $line['committedCents'];
                $totalPlanned += $line['plannedCents'];
            }
        }

        $totalConsumed = $totalSpent + $totalCommitted;

        return [
            'year' => $year,
            'month' => $month,
            'totalBudgetedCents' => $totalBudgeted,
            'totalSpentCents' => $totalSpent,
            'totalCommittedCents' => $totalCommitted,
            'totalPlannedCents' => $totalPlanned,
            'totalConsumedCents' => $totalConsumed,
            'totalRemainingCents' => $totalBudgeted - $totalConsumed,
            'totalAvailableCents' => $totalBudgeted - $totalConsumed - $totalPlanned,
            'budgets' => $budgets,
            'outsideMonthlyBudget' => [
                'annual' => $this->categoryLines($user, $annual, $childrenByParent),
                'oneOff' => $this->oneOffLines($user),
            ],
        ];
    }

    /**
     * One line per envelope of a category, nested among envelopes of the same
     * mode only: an annual envelope never holds a monthly one.
     *
     * @param list<Envelope>            $envelopes
     * @param array<string, list<Ulid>> $childrenByParent
     *
     * @return list<array<string, mixed>>
     */
    private function categoryLines(User $user, array $envelopes, array $childrenByParent): array
    {
        $envelopesByCategory = $this->nesting->byCategory($envelopes);

        // What each mother has handed down to her daughters, by mother id.
        $allocatedByMother = [];
        foreach ($envelopes as $envelope) {
            $mother = $this->nesting->motherOf($envelope, $envelopesByCategory);
            if (null !== $mother) {
                $key = (string) $mother->getId();
                $allocatedByMother[$key] = ($allocatedByMother[$key] ?? 0) + $envelope->getMonthAmountCents();
            }
        }

        $lines = [];
        foreach ($envelopes as $envelope) {
            $category = $envelope->requireCategory();
            $parentEnvelope = $this->nesting->motherOf($envelope, $envelopesByCategory);
            $allocatedToChildrenCents = $allocatedByMother[(string) $envelope->getId()] ?? null;

            $byStatus = $this->transactionRepository->sumByStatusForCategoriesBetween(
                $user,
                $this->selfAndDescendantIds($category, $childrenByParent),
                $envelope->getPeriodStart() ?? throw new \LogicException('A one-off envelope has no period.'),
                $envelope->getPeriodEnd() ?? throw new \LogicException('A one-off envelope has no period.'),
            );

            $lines[] = [
                'id' => (string) $envelope->getId(),
                'categoryId' => (string) $category->getId(),
                'categoryName' => $category->getName(),
                'categoryPath' => $category->getPath(),
                'parentEnvelopeId' => null === $parentEnvelope ? null : (string) $parentEnvelope->getId(),
                'categoryObligation' => $category->getObligation()->value,
                'mode' => $envelope->getMode()->value,
                'currency' => $envelope->getCurrency(),
                'year' => $envelope->getYear(),
                'month' => $envelope->getMonth(),
                // The season budgeting this month, if any: amountCents is then its amount (MAG-415).
                'season' => $envelope->getSeason(),
                // What the daughters hold and what is left free in a mother; null when she has no daughter.
                'allocatedToChildrenCents' => $allocatedToChildrenCents,
                'freeCents' => null === $allocatedToChildrenCents ? null : $envelope->getMonthAmountCents() - $allocatedToChildrenCents,
            ] + $this->consumption($envelope->getMonthAmountCents(), $byStatus) + [
                // Past it, a bank sync makes Maggie interrupt (MAG-49).
                'alertThresholdPercent' => $envelope->getEffectiveAlertThresholdPercent(),
            ];
        }

        return $lines;
    }

    /**
     * The one-off envelopes have no period: each weighs every spend ever
     * attributed to it.
     *
     * @return list<array<string, mixed>>
     */
    private function oneOffLines(User $user): array
    {
        $sums = $this->transactionRepository->sumByStatusForOneOffEnvelopes($user);
        $none = array_fill_keys(array_column(TransactionStatus::cases(), 'value'), 0);

        $lines = [];
        foreach ($this->envelopeRepository->findOneOffByUser($user) as $envelope) {
            $lines[] = [
                'id' => (string) $envelope->getId(),
                'name' => $envelope->getName(),
                'mode' => $envelope->getMode()->value,
                'currency' => $envelope->getCurrency(),
            ] + $this->consumption($envelope->getAmountCents(), $sums[(string) $envelope->getId()] ?? $none);
        }

        return $lines;
    }

    /**
     * @param array<string, int> $byStatus
     *
     * @return array<string, int|bool>
     */
    private function consumption(int $amountCents, array $byStatus): array
    {
        $spentCents = $byStatus[TransactionStatus::Spent->value];
        $committedCents = $byStatus[TransactionStatus::Committed->value];
        $plannedCents = $byStatus[TransactionStatus::Planned->value];
        $consumedCents = $spentCents + $committedCents;

        return [
            'amountCents' => $amountCents,
            'spentCents' => $spentCents,
            'committedCents' => $committedCents,
            'plannedCents' => $plannedCents,
            'toArbitrateCents' => $byStatus[TransactionStatus::ToArbitrate->value],
            // Money already gone, and what is left after it.
            'consumedCents' => $consumedCents,
            'remainingCents' => $amountCents - $consumedCents,
            // What is actually free to spend once plans are set aside.
            'availableCents' => $amountCents - $consumedCents - $plannedCents,
            'isOverspent' => $consumedCents > $amountCents,
            'isOvercommitted' => $consumedCents + $plannedCents > $amountCents,
        ];
    }

    /**
     * @param array<string, list<Ulid>> $childrenByParent
     *
     * @return list<Ulid>
     */
    private function selfAndDescendantIds(Category $category, array $childrenByParent): array
    {
        $ids = [$category->getId()];
        $queue = [(string) $category->getId()];
        while ([] !== $queue) {
            foreach ($childrenByParent[array_shift($queue)] ?? [] as $childId) {
                $ids[] = $childId;
                $queue[] = (string) $childId;
            }
        }

        return $ids;
    }
}
