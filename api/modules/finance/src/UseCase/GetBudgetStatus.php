<?php

declare(strict_types=1);

namespace Maggie\Finance\UseCase;

use Maggie\Core\Entity\User;
use Maggie\Finance\Entity\Category;
use Maggie\Finance\Entity\Envelope;
use Maggie\Finance\Enum\TransactionStatus;
use Maggie\Finance\Repository\CategoryRepository;
use Maggie\Finance\Repository\EnvelopeRepository;
use Maggie\Finance\Repository\TransactionRepository;
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
    ) {
    }

    /** @return array<string, mixed> */
    public function execute(User $user, int $year, int $month): array
    {
        $budgets = [];
        $totalBudgeted = 0;
        $totalSpent = 0;
        $totalCommitted = 0;
        $totalPlanned = 0;

        $envelopes = $this->envelopeRepository->findCoveringPeriod($user, $year, $month);

        $childrenByParent = [];
        foreach ($this->categoryRepository->findByUser($user) as $category) {
            if (null !== $category->getParent()) {
                $childrenByParent[(string) $category->getParent()->getId()][] = $category->getId();
            }
        }

        $envelopesByCategory = [];
        foreach ($envelopes as $envelope) {
            $envelopesByCategory[(string) $envelope->getCategory()->getId()][] = $envelope;
        }

        foreach ($envelopes as $envelope) {
            $parentEnvelope = $this->nearestEnvelopeAbove($envelope, $envelopesByCategory);

            $byStatus = $this->transactionRepository->sumByStatusForCategoriesBetween(
                $user,
                $this->selfAndDescendantIds($envelope->getCategory(), $childrenByParent),
                $envelope->getPeriodStart(),
                $envelope->getPeriodEnd(),
            );

            $spentCents = $byStatus[TransactionStatus::Spent->value];
            $committedCents = $byStatus[TransactionStatus::Committed->value];
            $plannedCents = $byStatus[TransactionStatus::Planned->value];
            $toArbitrateCents = $byStatus[TransactionStatus::ToArbitrate->value];

            $consumedCents = $spentCents + $committedCents;
            $amountCents = $envelope->getAmountCents();

            if (null === $parentEnvelope) {
                $totalBudgeted += $amountCents;
                $totalSpent += $spentCents;
                $totalCommitted += $committedCents;
                $totalPlanned += $plannedCents;
            }

            $budgets[] = [
                'id' => (string) $envelope->getId(),
                'categoryId' => (string) $envelope->getCategory()->getId(),
                'categoryName' => $envelope->getCategory()->getName(),
                'categoryPath' => $envelope->getCategory()->getPath(),
                'parentEnvelopeId' => null === $parentEnvelope ? null : (string) $parentEnvelope->getId(),
                'categoryObligation' => $envelope->getCategory()->getObligation()->value,
                'mode' => $envelope->getMode()->value,
                'amountCents' => $amountCents,
                'currency' => $envelope->getCurrency(),
                'year' => $envelope->getYear(),
                'month' => $envelope->getMonth(),
                'spentCents' => $spentCents,
                'committedCents' => $committedCents,
                'plannedCents' => $plannedCents,
                'toArbitrateCents' => $toArbitrateCents,
                // Money already gone, and what is left after it.
                'consumedCents' => $consumedCents,
                'remainingCents' => $amountCents - $consumedCents,
                // What is actually free to spend once plans are set aside.
                'availableCents' => $amountCents - $consumedCents - $plannedCents,
                'isOverspent' => $consumedCents > $amountCents,
                'isOvercommitted' => $consumedCents + $plannedCents > $amountCents,
            ];
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
        ];
    }

    /**
     * The envelope of the closest ancestor category that has one, the one of
     * the same mode when the ancestor has several.
     *
     * @param array<string, list<Envelope>> $envelopesByCategory
     */
    private function nearestEnvelopeAbove(Envelope $envelope, array $envelopesByCategory): ?Envelope
    {
        for ($ancestor = $envelope->getCategory()->getParent(); null !== $ancestor; $ancestor = $ancestor->getParent()) {
            $candidates = $envelopesByCategory[(string) $ancestor->getId()] ?? [];
            if ([] === $candidates) {
                continue;
            }

            foreach ($candidates as $candidate) {
                if ($candidate->getMode() === $envelope->getMode()) {
                    return $candidate;
                }
            }

            return $candidates[0];
        }

        return null;
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
