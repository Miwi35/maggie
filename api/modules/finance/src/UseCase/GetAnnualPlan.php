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
     * How much a debit must weigh to come back as a candidate. A threshold is
     * checkable by hand against the transaction list; a top N would depend on
     * what the other lines happen to be worth.
     */
    public const DEFAULT_THRESHOLD_CENTS = 10_000;

    public function __construct(
        private readonly EnvelopeRepository $envelopeRepository,
        private readonly TransactionRepository $transactionRepository,
    ) {
    }

    /**
     * The year the session is about when nobody says. It is an end-of-year
     * appointment (doc §5.3): in November it is next year that is being
     * planned, not the one closing.
     */
    public static function defaultYear(\DateTimeImmutable $now): int
    {
        $year = (int) $now->format('Y');

        return (int) $now->format('n') >= 11 ? $year + 1 : $year;
    }

    /** @return array<string, mixed> */
    public function execute(User $user, int $year, int $thresholdCents = self::DEFAULT_THRESHOLD_CENTS): array
    {
        $sourceYear = $year - 1;
        $sourceStart = new \DateTimeImmutable(sprintf('%04d-01-01', $sourceYear));
        $targetStart = $sourceStart->modify('+1 year');
        $targetEnd = $targetStart->modify('+1 year');

        /** @var array<string, array<string, mixed>> $rows keyed by category id */
        $rows = [];

        // What last year had been budgeted, and what it really consumed.
        foreach ($this->envelopeRepository->findForPeriod($user, $sourceYear, null) as $envelope) {
            $category = $envelope->getCategory();
            $id = $this->open($rows, $category);
            $rows[$id]['currency'] = $envelope->getCurrency();
            $rows[$id]['lastYear']['budgetedCents'] = $envelope->getAmountCents();
        }

        // The annual envelopes already set on the target year: re-opening the
        // session must show what a previous pass decided, not propose it again.
        foreach ($this->envelopeRepository->findForPeriod($user, $year, null) as $envelope) {
            $id = $this->open($rows, $envelope->getCategory());
            $rows[$id]['currency'] = $envelope->getCurrency();
            $rows[$id]['envelopeId'] = (string) $envelope->getId();
            $rows[$id]['envelopeCents'] = $envelope->getAmountCents();
        }

        // Last year's big expenses: the matter of the session.
        $notable = $this->transactionRepository
            ->findNotableDebitsBetween($user, $sourceStart, $targetStart, $thresholdCents);

        foreach ($notable as $transaction) {
            $id = $this->open($rows, $this->categoryOf($transaction));
            $rows[$id]['lastYear']['events'][] = $this->serialize($transaction, withId: false);
        }

        // What the target year has already decided on.
        foreach ($this->transactionRepository->findPlansBetween($user, $targetStart, $targetEnd) as $transaction) {
            $id = $this->open($rows, $this->categoryOf($transaction));
            $rows[$id]['plannedEvents'][] = $this->serialize($transaction, withId: true);

            $amount = abs($transaction->getAmountCents());

            // To-arbitrate is reported, never summed: that is the whole point
            // of the status (finance-annual-envelopes, decision 3).
            if (TransactionStatus::ToArbitrate === $transaction->getStatus()) {
                $rows[$id]['toArbitrateCents'] += $amount;
            } else {
                $rows[$id]['plannedCents'] += $amount;
            }
        }

        foreach ($rows as $id => $row) {
            $consumed = $this->consumedBy($user, $row['category'], $sourceStart, $targetStart);

            $rows[$id]['lastYear']['consumedCents'] = $consumed;
            // What the year ahead has already committed to, or failing that
            // what the year behind actually cost. Two rules, no rolling
            // average: the owner must be able to redo the sum by hand.
            $rows[$id]['suggestedCents'] = $row['plannedCents'] > 0 ? $row['plannedCents'] : $consumed;
            unset($rows[$id]['category']);
        }

        // Biggest decision first.
        $categories = array_values($rows);
        usort($categories, fn (array $a, array $b) => $b['suggestedCents'] <=> $a['suggestedCents']);

        return [
            'year' => $year,
            'sourceYear' => $sourceYear,
            'thresholdCents' => $thresholdCents,
            'totalLastYearConsumedCents' => $this->total($categories, fn (array $c) => $c['lastYear']['consumedCents']),
            'totalPlannedCents' => $this->total($categories, fn (array $c) => $c['plannedCents']),
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
    private function open(array &$rows, Category $category): string
    {
        $id = (string) $category->getId();

        $rows[$id] ??= [
            'categoryId' => $id,
            'categoryName' => $category->getName(),
            'currency' => 'EUR',
            'lastYear' => ['budgetedCents' => null, 'consumedCents' => 0, 'events' => []],
            'plannedCents' => 0,
            'toArbitrateCents' => 0,
            'plannedEvents' => [],
            'envelopeId' => null,
            'envelopeCents' => null,
            'suggestedCents' => 0,
            // Dropped before the row goes out; only the totals pass needs it.
            'category' => $category,
        ];

        return $id;
    }

    /** Spent plus committed on a category over the source year. */
    private function consumedBy(
        User $user,
        Category $category,
        \DateTimeImmutable $from,
        \DateTimeImmutable $until,
    ): int {
        $byStatus = $this->transactionRepository
            ->sumByStatusForCategoryBetween($user, $category, $from, $until);

        return $byStatus[TransactionStatus::Spent->value] + $byStatus[TransactionStatus::Committed->value];
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
        ];

        return $withId ? ['id' => (string) $transaction->getId()] + $event : $event;
    }
}
