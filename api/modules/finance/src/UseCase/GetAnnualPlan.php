<?php

declare(strict_types=1);

namespace Maggie\Finance\UseCase;

use Maggie\Core\Entity\User;
use Maggie\Finance\Entity\Category;
use Maggie\Finance\Entity\Transaction;
use Maggie\Finance\Enum\TransactionStatus;
use Maggie\Finance\Repository\EnvelopeRepository;
use Maggie\Finance\Repository\TransactionRepository;

/**
 * The yearly planning session, read side: what last year actually cost, which
 * of its big expenses are worth carrying over, what is already planned for the
 * year ahead, and how much each category should therefore be budgeted.
 *
 * Nothing is stored. The plan *is* the planned transactions and the annual
 * envelopes — a stored "validated plan" would be a second truth, and it would
 * start lying at the first purchase.
 */
class GetAnnualPlan
{
    /**
     * How much a debit must weigh to come back as a candidate to reconsider
     * one by one. A threshold is checkable by hand against the transaction
     * list; a top N would depend on what the other lines happen to be worth.
     */
    public const DEFAULT_THRESHOLD_CENTS = 10_000;

    public function __construct(
        private readonly EnvelopeRepository $envelopeRepository,
        private readonly TransactionRepository $transactionRepository,
    ) {
    }

    /**
     * @return array<string, mixed>
     *
     * @throws \InvalidArgumentException on a year or a threshold no session
     *                                   could be about
     */
    public function execute(User $user, int $year, int $thresholdCents = self::DEFAULT_THRESHOLD_CENTS): array
    {
        PlanningYear::assert($year);

        if ($thresholdCents < 0) {
            throw new \InvalidArgumentException('thresholdCents must be a positive integer of cents.');
        }

        $sourceYear = $year - 1;
        $sourceStart = new \DateTimeImmutable(sprintf('%04d-01-01', $sourceYear));
        $targetStart = $sourceStart->modify('+1 year');
        $targetEnd = $targetStart->modify('+1 year');

        /** @var array<string, array<string, mixed>> $rows keyed by category id */
        $rows = [];

        // Every category the year behind cost something, whatever the
        // threshold: it decides which expenses come back one by one, never
        // whether a category is in the session at all. A total that moved with
        // it would say last year cost less than it did.
        foreach ($this->transactionRepository->sumSpendingByCategoryBetween($user, $sourceStart, $targetStart) as $spending) {
            // Uncategorized: the session budgets categories, so there is
            // nowhere to put it.
            if (null === $spending['categoryId']) {
                continue;
            }

            $id = $this->open($rows, $spending['categoryId'], (string) $spending['categoryName']);
            $rows[$id]['lastYear']['consumedCents'] = $spending['spentCents'];
        }

        // What the year behind had been budgeted.
        foreach ($this->envelopeRepository->findForPeriod($user, $sourceYear, null) as $envelope) {
            $id = $this->openFor($rows, $envelope->getCategory());
            $rows[$id]['currency'] = $envelope->getCurrency();
            $rows[$id]['lastYear']['budgetedCents'] = $envelope->getAmountCents();
        }

        // The annual envelopes already set on the target year: re-opening the
        // session must show what a previous pass decided, not propose it again.
        foreach ($this->envelopeRepository->findForPeriod($user, $year, null) as $envelope) {
            $id = $this->openFor($rows, $envelope->getCategory());
            $rows[$id]['currency'] = $envelope->getCurrency();
            $rows[$id]['envelopeId'] = (string) $envelope->getId();
            $rows[$id]['envelopeCents'] = $envelope->getAmountCents();
        }

        // Last year's big expenses: the matter of the session.
        $notable = $this->transactionRepository
            ->findNotableDebitsBetween($user, $sourceStart, $targetStart, $thresholdCents);

        foreach ($notable as $transaction) {
            $id = $this->openFor($rows, $this->categoryOf($transaction));
            $rows[$id]['lastYear']['events'][] = $this->serialize($transaction, withId: false);
        }

        // What the target year has already decided on. The statuses are kept
        // apart and named as GetBudgetStatus names them, so one screen can
        // read both payloads without the same word meaning two things.
        foreach ($this->transactionRepository->findPlansBetween($user, $targetStart, $targetEnd) as $transaction) {
            $id = $this->openFor($rows, $this->categoryOf($transaction));
            $rows[$id]['plannedEvents'][] = $this->serialize($transaction, withId: true);

            $amount = abs($transaction->getAmountCents());

            $rows[$id][match ($transaction->getStatus()) {
                TransactionStatus::Committed => 'committedCents',
                // Reported, never summed into what the year owes: that is the
                // whole point of the status (finance-annual-envelopes, 3).
                TransactionStatus::ToArbitrate => 'toArbitrateCents',
                default => 'plannedCents',
            }] += $amount;
        }

        foreach ($rows as $id => $row) {
            // Money the year ahead has committed to one way or another.
            $decided = $row['plannedCents'] + $row['committedCents'];

            $rows[$id]['decidedCents'] = $decided;
            // What the year ahead has decided on, or failing that what the
            // year behind actually cost. Two rules, no rolling average: the
            // owner must be able to redo the sum by hand.
            $rows[$id]['suggestedCents'] = $decided > 0 ? $decided : $row['lastYear']['consumedCents'];
        }

        // Biggest decision first.
        $categories = array_values($rows);
        usort($categories, fn (array $a, array $b) => $b['suggestedCents'] <=> $a['suggestedCents']);

        return [
            'year' => $year,
            'sourceYear' => $sourceYear,
            'thresholdCents' => $thresholdCents,
            'totalLastYearConsumedCents' => $this->total($categories, fn (array $c) => $c['lastYear']['consumedCents']),
            'totalDecidedCents' => $this->total($categories, fn (array $c) => $c['decidedCents']),
            'totalToArbitrateCents' => $this->total($categories, fn (array $c) => $c['toArbitrateCents']),
            'totalSuggestedCents' => $this->total($categories, fn (array $c) => $c['suggestedCents']),
            'totalEnvelopedCents' => $this->total($categories, fn (array $c) => $c['envelopeCents'] ?? 0),
            'categories' => $categories,
        ];
    }

    /**
     * Makes sure the category has a row, and gives back its key.
     *
     * @param array<string, array<string, mixed>> $rows
     */
    private function open(array &$rows, string $id, string $name): string
    {
        $rows[$id] ??= [
            'categoryId' => $id,
            'categoryName' => $name,
            // The currency the envelope of this category is, or will be, set
            // in: an envelope of either year overwrites it, and `EUR` is what
            // setting one defaults to (ManageEnvelopesTool::set). Deliberately
            // not a transaction's — the lines of one category can be in
            // several currencies, and each event carries its own.
            'currency' => 'EUR',
            'lastYear' => ['budgetedCents' => null, 'consumedCents' => 0, 'events' => []],
            'plannedCents' => 0,
            'committedCents' => 0,
            'toArbitrateCents' => 0,
            'decidedCents' => 0,
            'plannedEvents' => [],
            'envelopeId' => null,
            'envelopeCents' => null,
            'suggestedCents' => 0,
        ];

        return $id;
    }

    /**
     * Same, from the entity.
     *
     * @param array<string, array<string, mixed>> $rows
     */
    private function openFor(array &$rows, Category $category): string
    {
        return $this->open($rows, (string) $category->getId(), $category->getName());
    }

    /**
     * @param list<array<string, mixed>>          $categories
     * @param callable(array<string, mixed>): int $of
     */
    private function total(array $categories, callable $of): int
    {
        return array_sum(array_map($of, $categories));
    }

    /** The repository queries only ever return categorized debits. */
    private function categoryOf(Transaction $transaction): Category
    {
        return $transaction->getCategory() ?? throw new \LogicException('A planned debit without a category.');
    }

    /**
     * An event of the session: a positive amount, and the month it falls in —
     * a planning session reasons in months, the day is made up.
     *
     * @return array<string, mixed>
     */
    private function serialize(Transaction $transaction, bool $withId): array
    {
        $event = [
            'label' => $transaction->getLabel(),
            'amountCents' => abs($transaction->getAmountCents()),
            'currency' => $transaction->getCurrency(),
            'month' => (int) $transaction->getBookedAt()->format('n'),
            'bookedAt' => $transaction->getBookedAt()->format('Y-m-d'),
            'status' => $transaction->getStatus()->value,
            'isExceptional' => $transaction->isExceptional(),
        ];

        return $withId ? ['id' => (string) $transaction->getId()] + $event : $event;
    }
}
