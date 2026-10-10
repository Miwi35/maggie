<?php

declare(strict_types=1);

namespace Maggie\Finance\Repository;

use Doctrine\Bundle\DoctrineBundle\Repository\ServiceEntityRepository;
use Doctrine\DBAL\ArrayParameterType;
use Doctrine\Persistence\ManagerRegistry;
use Maggie\Core\Entity\User;
use Maggie\Finance\Entity\Account;
use Maggie\Finance\Entity\Category;
use Maggie\Finance\Entity\Transaction;
use Maggie\Finance\Enum\CategorySource;
use Maggie\Finance\Enum\ObligationFlag;
use Maggie\Finance\Enum\TransactionStatus;
use Maggie\Finance\Enum\TransferKind;
use Maggie\Finance\Enum\TransferSource;
use Symfony\Component\Uid\Ulid;

/**
 * Every method that aggregates or lists movements keeps `transferKind = none`:
 * counting a movement between two of the owner's own accounts once as a debit
 * and once as a credit is what measured a 11 129 €/month lifestyle on 4 950 €
 * of income (MAG-271). `countMatching` (import de-duplication) and
 * `findByUser` / `findByAccount` (raw lists) filter nothing: a transfer is
 * still a line of the statement, and the balances already moved with the money.
 *
 * @extends ServiceEntityRepository<Transaction>
 */
class TransactionRepository extends ServiceEntityRepository
{
    public function __construct(ManagerRegistry $registry)
    {
        parent::__construct($registry, Transaction::class);
    }

    /** @return Transaction[] */
    public function findByUser(User $user): array
    {
        return $this->findBy(['user' => $user], ['bookedAt' => 'DESC']);
    }

    /**
     * One page of a user's lines, newest first, narrowed by the filters given,
     * with how many lines match in all. Like `findByUser`, transfers stay in;
     * the rejected payments are out unless `$transferKind` asks for them, as
     * the REST list does (MAG-375).
     *
     * @param \DateTimeImmutable|null $from        first booking day included
     * @param \DateTimeImmutable|null $to          last booking day included
     * @param string|null             $text        matched against the label and the counterparty, wildcards taken literally
     * @param string|null             $direction   `expense` (debits) or `income` (credits)
     * @param list<Ulid>|null         $categoryIds keeps the lines of these categories (a category and its sub-categories)
     *
     * @return array{transactions: Transaction[], total: int}
     */
    public function searchByUser(
        User $user,
        int $limit,
        ?string $accountId = null,
        ?\DateTimeImmutable $from = null,
        ?\DateTimeImmutable $to = null,
        ?string $text = null,
        ?string $direction = null,
        ?TransferKind $transferKind = null,
        ?array $categoryIds = null,
    ): array {
        $qb = $this->createQueryBuilder('t')
            ->andWhere('t.user = :user')
            ->setParameter('user', $user->getId(), 'ulid');

        if (null === $transferKind) {
            $qb->andWhere('t.transferKind != :rejected')->setParameter('rejected', TransferKind::Rejected->value);
        } else {
            $qb->andWhere('t.transferKind = :transferKind')->setParameter('transferKind', $transferKind->value);
        }

        if (null !== $accountId) {
            $qb->andWhere('t.account = :account')->setParameter('account', Ulid::fromString($accountId), 'ulid');
        }
        if (null !== $from) {
            $qb->andWhere('t.bookedAt >= :from')->setParameter('from', $from);
        }
        if (null !== $to) {
            $qb->andWhere('t.bookedAt <= :to')->setParameter('to', $to);
        }
        if (null !== $text && '' !== trim($text)) {
            $qb->andWhere('LOWER(t.label) LIKE :text OR LOWER(t.counterpartyName) LIKE :text')
                ->setParameter('text', '%'.addcslashes(mb_strtolower(trim($text)), '\\%_').'%');
        }
        if (null !== $categoryIds) {
            $qb->andWhere('t.category IN (:categories)')->setParameter('categories', array_map(static fn (Ulid $id): string => $id->toRfc4122(), $categoryIds), ArrayParameterType::STRING);
        }
        if ('expense' === $direction) {
            $qb->andWhere('t.amountCents < 0');
        } elseif ('income' === $direction) {
            $qb->andWhere('t.amountCents > 0');
        }

        $total = (int) (clone $qb)->select('COUNT(t.id)')->getQuery()->getSingleScalarResult();

        /** @var Transaction[] $transactions */
        $transactions = $qb
            ->orderBy('t.bookedAt', 'DESC')
            ->addOrderBy('t.id', 'DESC')
            ->setMaxResults($limit)
            ->getQuery()
            ->getResult();

        return ['transactions' => $transactions, 'total' => $total];
    }

    /** @return Transaction[] */
    public function findByAccount(Account $account): array
    {
        return $this->findBy(['account' => $account], ['bookedAt' => 'DESC']);
    }

    /**
     * The rejected lines of an account, with the leg each one points at
     * loaded in the same query. Either side of a pair may be what is found
     * here, so the caller folds the two legs into one incident.
     *
     * @return Transaction[]
     */
    public function findRejectedByAccount(Account $account): array
    {
        return $this->createQueryBuilder('t')
            ->leftJoin('t.counterpart', 'c')
            ->addSelect('c')
            ->andWhere('t.account = :account')
            ->andWhere('t.transferKind = :rejected')
            ->setParameter('account', $account->getId(), 'ulid')
            ->setParameter('rejected', TransferKind::Rejected->value)
            ->orderBy('t.bookedAt', 'DESC')
            ->addOrderBy('t.id', 'DESC')
            ->getQuery()
            ->getResult();
    }

    /**
     * Total spent on a category over a half-open period, as positive cents.
     * Only debits (negative amounts) count against a budget.
     */
    public function sumSpentForCategoryBetween(User $user, Category $category, \DateTimeImmutable $from, \DateTimeImmutable $until): int
    {
        $byStatus = $this->sumByStatusForCategoryBetween($user, $category, $from, $until);

        return $byStatus[TransactionStatus::Spent->value];
    }

    /**
     * Debits on a category over a half-open period, split by status, as
     * positive cents. Every status is present, zero when nothing matched.
     *
     * @return array<string, int>
     */
    public function sumByStatusForCategoryBetween(User $user, Category $category, \DateTimeImmutable $from, \DateTimeImmutable $until): array
    {
        $rows = $this->createQueryBuilder('t')
            ->select('t.status AS status, SUM(t.amountCents) AS total')
            ->andWhere('t.user = :user')
            ->andWhere('t.transferKind = :noTransfer')
            ->andWhere('t.category = :category')
            ->andWhere('t.amountCents < 0')
            ->andWhere('t.bookedAt >= :from')
            ->andWhere('t.bookedAt < :until')
            ->setParameter('user', $user->getId(), 'ulid')
            ->setParameter('noTransfer', TransferKind::None->value)
            ->setParameter('category', $category->getId(), 'ulid')
            ->setParameter('from', $from)
            ->setParameter('until', $until)
            ->groupBy('t.status')
            ->getQuery()
            ->getResult();

        $totals = [];
        foreach (TransactionStatus::cases() as $status) {
            $totals[$status->value] = 0;
        }

        foreach ($rows as $row) {
            $status = $row['status'] instanceof TransactionStatus ? $row['status']->value : (string) $row['status'];
            $totals[$status] = abs((int) $row['total']);
        }

        return $totals;
    }

    /**
     * Transactions still up for grabs by the rule engine: no category yet, and
     * not deliberately left uncategorized by hand.
     *
     * @return Transaction[]
     */
    public function findUncategorizedForUser(User $user): array
    {
        return $this->createQueryBuilder('t')
            ->andWhere('t.user = :user')
            ->andWhere('t.category IS NULL')
            ->andWhere('t.categorySource != :manual')
            ->setParameter('user', $user->getId(), 'ulid')
            ->setParameter('manual', CategorySource::Manual->value)
            ->orderBy('t.bookedAt', 'DESC')
            ->getQuery()
            ->getResult();
    }

    /** How many lines {@see findUncategorizedForUser} would return, without loading them. */
    public function countUncategorizedForUser(User $user): int
    {
        return (int) $this->createQueryBuilder('t')
            ->select('COUNT(t.id)')
            ->andWhere('t.user = :user')
            ->andWhere('t.category IS NULL')
            ->andWhere('t.categorySource != :manual')
            ->setParameter('user', $user->getId(), 'ulid')
            ->setParameter('manual', CategorySource::Manual->value)
            ->getQuery()
            ->getSingleScalarResult();
    }

    /**
     * The lines of one user the detection may still pair, oldest first: the
     * paired ones and the ones he judged by hand are left out, so a second
     * catch-up pass over the same history changes nothing.
     *
     * @param \DateTimeImmutable|null $since how far back to look, or null for everything
     *
     * @return Transaction[]
     */
    public function findUnpairedForUser(User $user, ?\DateTimeImmutable $since = null): array
    {
        $qb = $this->createQueryBuilder('t')
            ->innerJoin('t.account', 'a')
            ->andWhere('t.user = :user')
            ->andWhere('a.user = :user')
            ->andWhere('t.counterpart IS NULL')
            ->andWhere('t.transferSource != :manual')
            ->andWhere('t.status IN (:consumed)')
            ->setParameter('user', $user->getId(), 'ulid')
            ->setParameter('manual', TransferSource::Manual->value)
            ->setParameter('consumed', [TransactionStatus::Spent->value, TransactionStatus::Committed->value])
            ->orderBy('t.bookedAt', 'ASC')
            ->addOrderBy('t.id', 'ASC');

        if (null !== $since) {
            $qb->andWhere('t.bookedAt >= :since')->setParameter('since', $since);
        }

        return $qb->getQuery()->getResult();
    }

    /**
     * Every line that could be the other leg of this movement, in one query.
     *
     * Same user, two accounts of his that are not the same one, exactly
     * opposite amounts in the same currency, booked within the window, already
     * consumed, not paired yet and not judged by hand. Which one wins is the
     * use case's call; the order here only makes the result stable. A line
     * left a rejection — even one whose other leg was deleted — is not a
     * transfer, whoever asks.
     *
     * `$includeJudged` keeps the lines the owner already took out of the
     * transfers: the detection must skip them, but the owner choosing a
     * counterpart by hand may well want one back. `$sameAccount` looks on the
     * line's own account instead, where a rejected payment is given back.
     *
     * @return Transaction[]
     */
    public function findTransferCandidates(Transaction $transaction, int $windowDays, bool $includeJudged = false, bool $sameAccount = false): array
    {
        $bookedAt = $transaction->getBookedAt();

        $qb = $this->createQueryBuilder('t')
            ->innerJoin('t.account', 'a')
            ->andWhere('t.user = :user')
            ->andWhere('a.user = :user')
            ->andWhere('t.id != :id')
            ->andWhere($sameAccount ? 't.account = :account' : 't.account != :account')
            ->andWhere('t.amountCents = :opposite')
            ->andWhere('t.currency = :currency')
            ->andWhere('t.bookedAt >= :from')
            ->andWhere('t.bookedAt <= :until')
            ->andWhere('t.status IN (:consumed)')
            ->andWhere('t.counterpart IS NULL')
            ->andWhere('t.transferKind != :rejected')
            ->setParameter('user', $transaction->getUser()->getId(), 'ulid')
            ->setParameter('id', $transaction->getId(), 'ulid')
            ->setParameter('account', $transaction->getAccount()->getId(), 'ulid')
            ->setParameter('rejected', TransferKind::Rejected->value)
            ->setParameter('opposite', -$transaction->getAmountCents())
            ->setParameter('currency', $transaction->getCurrency())
            ->setParameter('from', $bookedAt->modify(sprintf('-%d days', $windowDays)))
            ->setParameter('until', $bookedAt->modify(sprintf('+%d days', $windowDays)))
            ->setParameter('consumed', [TransactionStatus::Spent->value, TransactionStatus::Committed->value])
            ->orderBy('t.bookedAt', 'ASC')
            ->addOrderBy('t.id', 'ASC');

        if (!$includeJudged) {
            $qb->andWhere('t.transferSource != :manual')
                ->setParameter('manual', TransferSource::Manual->value);
        }

        return $qb->getQuery()->getResult();
    }

    /**
     * The credits of one user that may still cancel a rejected payment,
     * oldest first: unpaired, consumed, not judged by hand. Which of them read
     * as a rejection is the use case's call, on the label.
     *
     * @param \DateTimeImmutable|null $since how far back to look, or null for everything
     *
     * @return Transaction[]
     */
    public function findUnpairedCreditsForUser(User $user, ?\DateTimeImmutable $since = null): array
    {
        $qb = $this->createQueryBuilder('t')
            ->innerJoin('t.account', 'a')
            ->andWhere('t.user = :user')
            ->andWhere('a.user = :user')
            ->andWhere('t.amountCents > 0')
            ->andWhere('t.counterpart IS NULL')
            ->andWhere('t.transferKind = :none')
            ->andWhere('t.transferSource != :manual')
            ->andWhere('t.status IN (:consumed)')
            ->setParameter('user', $user->getId(), 'ulid')
            ->setParameter('none', TransferKind::None->value)
            ->setParameter('manual', TransferSource::Manual->value)
            ->setParameter('consumed', [TransactionStatus::Spent->value, TransactionStatus::Committed->value])
            ->orderBy('t.bookedAt', 'ASC')
            ->addOrderBy('t.id', 'ASC');

        if (null !== $since) {
            $qb->andWhere('t.bookedAt >= :since')->setParameter('since', $since);
        }

        return $qb->getQuery()->getResult();
    }

    /**
     * The debits a rejection credit may cancel: the same account, the exact
     * opposite amount in the same currency, booked on the credit's day or in
     * the days before it, consumed, still an ordinary line and not judged by
     * hand. Whether the payee matches is the use case's call.
     *
     * `$includeJudged` keeps the lines the owner took out by hand, for the
     * owner choosing the rejected payment himself.
     *
     * @return Transaction[]
     */
    public function findRejectedDebitCandidates(Transaction $credit, int $windowDays, bool $includeJudged = false): array
    {
        $bookedAt = $credit->getBookedAt();

        $qb = $this->createQueryBuilder('t')
            ->andWhere('t.user = :user')
            ->andWhere('t.id != :id')
            ->andWhere('t.account = :account')
            ->andWhere('t.amountCents = :opposite')
            ->andWhere('t.amountCents < 0')
            ->andWhere('t.currency = :currency')
            ->andWhere('t.bookedAt >= :from')
            ->andWhere('t.bookedAt <= :until')
            ->andWhere('t.status IN (:consumed)')
            ->andWhere('t.counterpart IS NULL')
            ->andWhere('t.transferKind = :none')
            ->setParameter('user', $credit->getUser()->getId(), 'ulid')
            ->setParameter('id', $credit->getId(), 'ulid')
            ->setParameter('account', $credit->getAccount()->getId(), 'ulid')
            ->setParameter('opposite', -$credit->getAmountCents())
            ->setParameter('currency', $credit->getCurrency())
            ->setParameter('from', $bookedAt->modify(sprintf('-%d days', $windowDays)))
            ->setParameter('until', $bookedAt)
            ->setParameter('consumed', [TransactionStatus::Spent->value, TransactionStatus::Committed->value])
            ->setParameter('none', TransferKind::None->value)
            ->orderBy('t.bookedAt', 'DESC')
            ->addOrderBy('t.id', 'ASC');

        if (!$includeJudged) {
            $qb->andWhere('t.transferSource != :manual')
                ->setParameter('manual', TransferSource::Manual->value);
        }

        return $qb->getQuery()->getResult();
    }

    /**
     * Everyday consumption over a half-open period, as positive cents: spent
     * and committed debits, without the exceptional ones and the loan
     * payments (counted on their own by the debt timeline).
     */
    public function sumConsumedBetween(User $user, \DateTimeImmutable $from, \DateTimeImmutable $until): int
    {
        $total = $this->createQueryBuilder('t')
            ->select('SUM(t.amountCents)')
            ->leftJoin('t.category', 'c')
            ->andWhere('t.user = :user')
            ->andWhere('t.transferKind = :noTransfer')
            ->andWhere('t.amountCents < 0')
            ->andWhere('t.status IN (:consumed)')
            ->andWhere('t.isExceptional = false')
            ->andWhere('c.id IS NULL OR c.obligation != :debt')
            ->andWhere('t.bookedAt >= :from')
            ->andWhere('t.bookedAt < :until')
            ->setParameter('user', $user->getId(), 'ulid')
            ->setParameter('noTransfer', TransferKind::None->value)
            ->setParameter('consumed', [TransactionStatus::Spent->value, TransactionStatus::Committed->value])
            ->setParameter('debt', ObligationFlag::Debt->value)
            ->setParameter('from', $from)
            ->setParameter('until', $until)
            ->getQuery()
            ->getSingleScalarResult();

        return abs((int) $total);
    }

    /**
     * The big debits of a period: what an annual planning session reconsiders,
     * biggest first. Categorized only — the session budgets categories, so an
     * uncategorized debit has nowhere to go.
     *
     * @param int $minAmountCents how much a debit must weigh to be worth a
     *                            second look, as positive cents
     *
     * @return Transaction[]
     */
    public function findNotableDebitsBetween(
        User $user,
        \DateTimeImmutable $from,
        \DateTimeImmutable $until,
        int $minAmountCents,
    ): array {
        return $this->createQueryBuilder('t')
            ->andWhere('t.user = :user')
            ->andWhere('t.transferKind = :noTransfer')
            ->andWhere('t.category IS NOT NULL')
            ->andWhere('t.amountCents < 0')
            ->andWhere('t.amountCents <= :ceiling')
            ->andWhere('t.status IN (:consumed)')
            ->andWhere('t.bookedAt >= :from')
            ->andWhere('t.bookedAt < :until')
            ->setParameter('user', $user->getId(), 'ulid')
            ->setParameter('noTransfer', TransferKind::None->value)
            ->setParameter('ceiling', -$minAmountCents)
            ->setParameter('consumed', [TransactionStatus::Spent->value, TransactionStatus::Committed->value])
            ->setParameter('from', $from)
            ->setParameter('until', $until)
            ->orderBy('t.amountCents', 'ASC')
            ->getQuery()
            ->getResult();
    }

    /**
     * The debits a period has already decided on without having spent them:
     * planned, committed and to-arbitrate. Categorized only, in date order.
     *
     * @return Transaction[]
     */
    public function findPlansBetween(User $user, \DateTimeImmutable $from, \DateTimeImmutable $until): array
    {
        return $this->createQueryBuilder('t')
            ->andWhere('t.user = :user')
            ->andWhere('t.transferKind = :noTransfer')
            ->andWhere('t.category IS NOT NULL')
            ->andWhere('t.amountCents < 0')
            ->andWhere('t.status IN (:decided)')
            ->andWhere('t.bookedAt >= :from')
            ->andWhere('t.bookedAt < :until')
            ->setParameter('user', $user->getId(), 'ulid')
            ->setParameter('noTransfer', TransferKind::None->value)
            ->setParameter('decided', [
                TransactionStatus::Planned->value,
                TransactionStatus::Committed->value,
                TransactionStatus::ToArbitrate->value,
            ])
            ->setParameter('from', $from)
            ->setParameter('until', $until)
            ->orderBy('t.bookedAt', 'ASC')
            ->getQuery()
            ->getResult();
    }

    /**
     * What the rentes brought in over a half-open period, by category, as
     * positive cents, biggest first.
     *
     * Only credits of the categories the user declared as rentes, and only
     * what actually landed: an exceptional credit is a one-off — a sale, a
     * refund — and counting it as a rente would promise an income that never
     * comes back.
     *
     * The category is scoped to the user as well as the transaction: nothing
     * stops a transaction from pointing at someone else's category, and the
     * counter would then sum — and name — a rente that is not his.
     *
     * @return array<int, array{categoryId: string, categoryName: string, incomeCents: int}>
     */
    public function sumPassiveIncomeByCategoryBetween(User $user, \DateTimeImmutable $from, \DateTimeImmutable $until): array
    {
        $rows = $this->createQueryBuilder('t')
            ->select('IDENTITY(t.category) AS categoryId, c.name AS categoryName, SUM(t.amountCents) AS total')
            ->innerJoin('t.category', 'c')
            ->andWhere('t.user = :user')
            ->andWhere('t.transferKind = :noTransfer')
            ->andWhere('c.user = :user')
            ->andWhere('c.passiveIncome = true')
            ->andWhere('t.amountCents > 0')
            ->andWhere('t.status IN (:consumed)')
            ->andWhere('t.isExceptional = false')
            ->andWhere('t.bookedAt >= :from')
            ->andWhere('t.bookedAt < :until')
            ->setParameter('user', $user->getId(), 'ulid')
            ->setParameter('noTransfer', TransferKind::None->value)
            ->setParameter('consumed', [TransactionStatus::Spent->value, TransactionStatus::Committed->value])
            ->setParameter('from', $from)
            ->setParameter('until', $until)
            ->groupBy('categoryId')
            ->addGroupBy('c.name')
            ->getQuery()
            ->getResult();

        $income = array_map(static fn (array $row) => [
            'categoryId' => (string) $row['categoryId'],
            'categoryName' => (string) $row['categoryName'],
            'incomeCents' => (int) $row['total'],
        ], $rows);

        usort($income, static fn (array $a, array $b) => $b['incomeCents'] <=> $a['incomeCents']);

        return $income;
    }

    /**
     * Debits of a period that are up for review: everything outside the
     * mandatory categories, uncategorized spends included — those are
     * precisely the ones worth a second look. Biggest first.
     *
     * @return Transaction[]
     */
    public function findReviewableBetween(User $user, \DateTimeImmutable $from, \DateTimeImmutable $until): array
    {
        return $this->createQueryBuilder('t')
            ->leftJoin('t.category', 'c')
            ->andWhere('t.user = :user')
            ->andWhere('t.transferKind = :noTransfer')
            ->andWhere('t.amountCents < 0')
            ->andWhere('t.status IN (:consumed)')
            ->andWhere('t.bookedAt >= :from')
            ->andWhere('t.bookedAt < :until')
            ->andWhere('c.id IS NULL OR c.obligation != :mandatory')
            ->setParameter('user', $user->getId(), 'ulid')
            ->setParameter('noTransfer', TransferKind::None->value)
            ->setParameter('consumed', [TransactionStatus::Spent->value, TransactionStatus::Committed->value])
            ->setParameter('from', $from)
            ->setParameter('until', $until)
            ->setParameter('mandatory', ObligationFlag::Mandatory->value)
            ->orderBy('t.amountCents', 'ASC')
            ->getQuery()
            ->getResult();
    }

    /**
     * Money in and money out, month by month, over a half-open period.
     * Only consumed movements count; both figures come back positive.
     *
     * Grouping happens in PHP: DQL has no portable way to take the year-month
     * out of a date, and a window of a year of movements is small.
     *
     * @return array<string, array{incomeCents: int, expenseCents: int}> keyed by "Y-m"
     */
    public function sumMonthlyFlowsBetween(User $user, \DateTimeImmutable $from, \DateTimeImmutable $until): array
    {
        /** @var array<int, array{bookedAt: \DateTimeImmutable, amountCents: int}> $rows */
        $rows = $this->createQueryBuilder('t')
            ->select('t.bookedAt AS bookedAt, t.amountCents AS amountCents')
            ->andWhere('t.user = :user')
            ->andWhere('t.transferKind = :noTransfer')
            ->andWhere('t.status IN (:consumed)')
            ->andWhere('t.bookedAt >= :from')
            ->andWhere('t.bookedAt < :until')
            ->setParameter('user', $user->getId(), 'ulid')
            ->setParameter('noTransfer', TransferKind::None->value)
            ->setParameter('consumed', [TransactionStatus::Spent->value, TransactionStatus::Committed->value])
            ->setParameter('from', $from)
            ->setParameter('until', $until)
            ->getQuery()
            ->getResult();

        $flows = [];
        foreach ($rows as $row) {
            $month = $row['bookedAt']->format('Y-m');
            $flows[$month] ??= ['incomeCents' => 0, 'expenseCents' => 0];

            if ($row['amountCents'] >= 0) {
                $flows[$month]['incomeCents'] += $row['amountCents'];
            } else {
                $flows[$month]['expenseCents'] += abs($row['amountCents']);
            }
        }

        return $flows;
    }

    /**
     * What each category cost over a half-open period, as positive cents,
     * biggest first. Uncategorized spending comes back under a null id.
     *
     * @return array<int, array{categoryId: ?string, categoryName: ?string, spentCents: int}>
     */
    public function sumSpendingByCategoryBetween(User $user, \DateTimeImmutable $from, \DateTimeImmutable $until): array
    {
        $rows = $this->createQueryBuilder('t')
            ->select('IDENTITY(t.category) AS categoryId, c.name AS categoryName, SUM(t.amountCents) AS total')
            ->leftJoin('t.category', 'c')
            ->andWhere('t.user = :user')
            ->andWhere('t.transferKind = :noTransfer')
            ->andWhere('t.amountCents < 0')
            ->andWhere('t.status IN (:consumed)')
            ->andWhere('t.bookedAt >= :from')
            ->andWhere('t.bookedAt < :until')
            ->setParameter('user', $user->getId(), 'ulid')
            ->setParameter('noTransfer', TransferKind::None->value)
            ->setParameter('consumed', [TransactionStatus::Spent->value, TransactionStatus::Committed->value])
            ->setParameter('from', $from)
            ->setParameter('until', $until)
            ->groupBy('categoryId')
            ->addGroupBy('c.name')
            ->getQuery()
            ->getResult();

        // `IDENTITY()` hands back the column's raw value (RFC 4122, 36
        // characters), not the ULID's own spelling (26 characters in base32)
        // that every other entry point of the API renders.
        $spending = array_map(static fn (array $row) => [
            'categoryId' => null === $row['categoryId'] ? null : (string) Ulid::fromString($row['categoryId']),
            'categoryName' => $row['categoryName'],
            'spentCents' => abs((int) $row['total']),
        ], $rows);

        usort($spending, static fn (array $a, array $b) => $b['spentCents'] <=> $a['spentCents']);

        return $spending;
    }

    /**
     * How many movements already recorded on this account look exactly like
     * this one. Counting matters: two identical coffees on the same day are
     * two real movements, not a duplicate — only the count above what is
     * already stored should be imported.
     *
     * @param list<string> $alternativeLabels other labels the same movement may
     *                                        have been stored under
     */
    public function countMatching(Account $account, \DateTimeImmutable $bookedAt, int $amountCents, string $label, array $alternativeLabels = []): int
    {
        return \count($this->findMatching($account, $bookedAt, $amountCents, $label, $alternativeLabels));
    }

    /**
     * The movements `countMatching` counts, in a stable order so the nth
     * occurrence in a file is always the nth stored line.
     *
     * @param list<string> $alternativeLabels
     *
     * @return Transaction[]
     */
    public function findMatching(Account $account, \DateTimeImmutable $bookedAt, int $amountCents, string $label, array $alternativeLabels = []): array
    {
        $labels = array_values(array_unique(array_map(
            static fn (string $candidate) => mb_strtolower(trim($candidate)),
            [$label, ...$alternativeLabels],
        )));

        return $this->createQueryBuilder('t')
            ->andWhere('t.account = :account')
            ->andWhere('t.bookedAt = :bookedAt')
            ->andWhere('t.amountCents = :amountCents')
            ->andWhere('LOWER(t.label) IN (:labels)')
            ->setParameter('account', $account->getId(), 'ulid')
            ->setParameter('bookedAt', $bookedAt)
            ->setParameter('amountCents', $amountCents)
            ->setParameter('labels', $labels)
            ->orderBy('t.id', 'ASC')
            ->getQuery()
            ->getResult();
    }

    /** The movement the bank already sent under this reference, on this account. */
    public function findOneByExternalId(Account $account, string $externalId): ?Transaction
    {
        return $this->createQueryBuilder('t')
            ->andWhere('t.account = :account')
            ->andWhere('t.externalId = :externalId')
            ->setParameter('account', $account->getId(), 'ulid')
            ->setParameter('externalId', $externalId)
            ->setMaxResults(1)
            ->getQuery()
            ->getOneOrNullResult();
    }

    /**
     * Transactions that have no counterparty yet, oldest first.
     *
     * @return iterable<Transaction>
     */
    public function iterateWithoutCounterparty(): iterable
    {
        return $this->createQueryBuilder('t')
            ->andWhere('t.counterpartyKey IS NULL')
            ->orderBy('t.id', 'ASC')
            ->getQuery()
            ->toIterable();
    }
}
