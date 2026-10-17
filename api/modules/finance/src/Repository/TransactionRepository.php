<?php

declare(strict_types=1);

namespace Maggie\Finance\Repository;

use Doctrine\Bundle\DoctrineBundle\Repository\ServiceEntityRepository;
use Doctrine\DBAL\ArrayParameterType;
use Doctrine\ORM\QueryBuilder;
use Doctrine\Persistence\ManagerRegistry;
use Maggie\Core\Entity\User;
use Maggie\Finance\Category\StandardCategories;
use Maggie\Finance\Entity\Account;
use Maggie\Finance\Entity\Category;
use Maggie\Finance\Entity\Contact;
use Maggie\Finance\Entity\Envelope;
use Maggie\Finance\Entity\RecurringOperation;
use Maggie\Finance\Entity\Transaction;
use Maggie\Finance\Enum\AccountType;
use Maggie\Finance\Enum\CategorySource;
use Maggie\Finance\Enum\QualificationKind;
use Maggie\Finance\Enum\RecurringLinkSource;
use Maggie\Finance\Enum\RetrospectVerdict;
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
    /**
     * `Transaction::getOwnAmountCents()` in DQL: the part the owner kept of a
     * shared spend (MAG-451), the whole amount less what gives back a debt
     * otherwise (MAG-453). Every budget sum reads it — envelopes, score,
     * review, lifestyle.
     */
    public const string OWN_AMOUNT = 'COALESCE(0 - t.myShareCents, CASE WHEN t.amountCents < 0 THEN t.amountCents + t.peerAllocatedCents ELSE t.amountCents - t.peerAllocatedCents END)';

    /**
     * What a received transfer carries as a dette (MAG-452): the amount to give
     * back to the person who sent it, 0 when the transfer is a plain income.
     * Money that is owed back is no income. DQL cannot subtract a sub-select
     * inside an aggregate: it is selected apart and taken off in PHP.
     */
    public const string DEBT_AMOUNT = '(SELECT COALESCE(SUM(debtShare.amountCents), 0) FROM Maggie\\Finance\\Entity\\PeerDebtShare debtShare INNER JOIN debtShare.peerDebt debt WHERE debt.originTransaction = t AND debt.direction = \'i_owe\')';

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
     * The lines of an account booked from one day to another, both included,
     * newest first, with the other leg of a transfer and its account loaded in
     * the same query. The rejected payments are left out: they have their own
     * list (MAG-375).
     *
     * @return Transaction[]
     */
    public function findByAccountBetween(Account $account, \DateTimeImmutable $from, \DateTimeImmutable $until): array
    {
        return $this->createQueryBuilder('t')
            ->leftJoin('t.counterpart', 'c')
            ->leftJoin('c.account', 'ca')
            ->addSelect('c', 'ca')
            ->andWhere('t.account = :account')
            ->andWhere('t.transferKind != :rejected')
            ->andWhere('t.bookedAt >= :from')
            ->andWhere('t.bookedAt <= :until')
            ->setParameter('account', $account->getId(), 'ulid')
            ->setParameter('rejected', TransferKind::Rejected->value)
            ->setParameter('from', $from, 'date_immutable')
            ->setParameter('until', $until, 'date_immutable')
            ->orderBy('t.bookedAt', 'DESC')
            ->addOrderBy('t.id', 'DESC')
            ->getQuery()
            ->getResult();
    }

    /** The day of the latest line of an account booked before a day, rejected payments aside; null when there is none. */
    public function findLatestBookingDayBefore(Account $account, \DateTimeImmutable $day): ?\DateTimeImmutable
    {
        $latest = $this->createQueryBuilder('t')
            ->select('MAX(t.bookedAt)')
            ->andWhere('t.account = :account')
            ->andWhere('t.transferKind != :rejected')
            ->andWhere('t.bookedAt < :day')
            ->setParameter('account', $account->getId(), 'ulid')
            ->setParameter('rejected', TransferKind::Rejected->value)
            ->setParameter('day', $day, 'date_immutable')
            ->getQuery()
            ->getSingleScalarResult();

        return null === $latest ? null : new \DateTimeImmutable((string) $latest);
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
     * The user's transactions among these ids — someone else's are not found.
     *
     * @param list<string> $ids
     *
     * @return list<Transaction>
     */
    public function findByIdsForUser(User $user, array $ids): array
    {
        $ids = array_values(array_filter($ids, static fn (string $id) => Ulid::isValid($id)));
        if ([] === $ids) {
            return [];
        }

        return $this->createQueryBuilder('t')
            ->andWhere('t.user = :user')
            ->andWhere('t.id IN (:ids)')
            ->setParameter('user', $user->getId(), 'ulid')
            ->setParameter('ids', array_map(static fn (string $id): string => Ulid::fromString($id)->toRfc4122(), $ids), ArrayParameterType::STRING)
            ->getQuery()
            ->getResult();
    }

    /**
     * Debits on a set of categories (a category and its descendants, for an
     * envelope that takes its sub-categories along) over a half-open period,
     * split by status, as positive cents. Every status is present, zero when
     * nothing matched. A spend attributed to a one-off envelope keeps its
     * category but leaves these sums: it is budgeted by its event.
     *
     * @param list<Ulid> $categoryIds
     *
     * @return array<string, int>
     */
    public function sumByStatusForCategoriesBetween(User $user, array $categoryIds, \DateTimeImmutable $from, \DateTimeImmutable $until): array
    {
        $rows = $this->createQueryBuilder('t')
            ->select('t.status AS status, SUM('.self::OWN_AMOUNT.') AS total')
            ->andWhere('t.user = :user')
            ->andWhere('t.transferKind = :noTransfer')
            ->andWhere('t.category IN (:categories)')
            ->andWhere('t.oneOffEnvelope IS NULL')
            ->andWhere('t.amountCents < 0')
            ->andWhere('t.bookedAt >= :from')
            ->andWhere('t.bookedAt < :until')
            ->setParameter('user', $user->getId(), 'ulid')
            ->setParameter('noTransfer', TransferKind::None->value)
            ->setParameter('categories', array_map(static fn (Ulid $id): string => $id->toRfc4122(), $categoryIds), ArrayParameterType::STRING)
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
     * The part of {@see sumByStatusForCategoriesBetween}'s spent and
     * committed debits that the transactions themselves qualify as mandatory,
     * as positive cents. A category holds mandatory and optional lines side
     * by side: the qualification decides, not the category.
     *
     * @param list<Ulid> $categoryIds
     */
    public function sumMandatoryConsumedForCategoriesBetween(User $user, array $categoryIds, \DateTimeImmutable $from, \DateTimeImmutable $until): int
    {
        $total = $this->createQueryBuilder('t')
            ->select('SUM('.self::OWN_AMOUNT.')')
            ->andWhere('t.user = :user')
            ->andWhere('t.transferKind = :noTransfer')
            ->andWhere('t.category IN (:categories)')
            ->andWhere('t.oneOffEnvelope IS NULL')
            ->andWhere('t.amountCents < 0')
            ->andWhere('t.status IN (:consumed)')
            ->andWhere('t.qualification.mandatory = true')
            ->andWhere('t.bookedAt >= :from')
            ->andWhere('t.bookedAt < :until')
            ->setParameter('user', $user->getId(), 'ulid')
            ->setParameter('noTransfer', TransferKind::None->value)
            ->setParameter('categories', array_map(static fn (Ulid $id): string => $id->toRfc4122(), $categoryIds), ArrayParameterType::STRING)
            ->setParameter('consumed', [TransactionStatus::Spent->value, TransactionStatus::Committed->value])
            ->setParameter('from', $from)
            ->setParameter('until', $until)
            ->getQuery()
            ->getSingleScalarResult();

        return abs((int) $total);
    }

    /**
     * What the owner thinks of the spent and committed debits of a set of
     * categories over a half-open period, as positive cents per verdict
     * (`keep`, `avoidable`; the lines not judged are left out). Same scope as
     * {@see sumByStatusForCategoriesBetween}.
     *
     * @param list<Ulid> $categoryIds
     *
     * @return array{keep: int, avoidable: int}
     */
    public function sumJudgedConsumedForCategoriesBetween(User $user, array $categoryIds, \DateTimeImmutable $from, \DateTimeImmutable $until): array
    {
        $totals = ['keep' => 0, 'avoidable' => 0];
        if ([] === $categoryIds) {
            return $totals;
        }

        $rows = $this->createQueryBuilder('t')
            ->select('t.retrospect AS verdict, SUM(t.amountCents) AS total')
            ->andWhere('t.user = :user')
            ->andWhere('t.transferKind = :noTransfer')
            ->andWhere('t.category IN (:categories)')
            ->andWhere('t.oneOffEnvelope IS NULL')
            ->andWhere('t.amountCents < 0')
            ->andWhere('t.status IN (:consumed)')
            ->andWhere('t.retrospect IN (:judged)')
            ->andWhere('t.bookedAt >= :from')
            ->andWhere('t.bookedAt < :until')
            ->setParameter('user', $user->getId(), 'ulid')
            ->setParameter('noTransfer', TransferKind::None->value)
            ->setParameter('categories', array_map(static fn (Ulid $id): string => $id->toRfc4122(), $categoryIds), ArrayParameterType::STRING)
            ->setParameter('consumed', [TransactionStatus::Spent->value, TransactionStatus::Committed->value])
            ->setParameter('judged', [RetrospectVerdict::Keep->value, RetrospectVerdict::Avoidable->value])
            ->setParameter('from', $from)
            ->setParameter('until', $until)
            ->groupBy('t.retrospect')
            ->getQuery()
            ->getResult();

        foreach ($rows as $row) {
            $verdict = $row['verdict'] instanceof RetrospectVerdict ? $row['verdict']->value : (string) $row['verdict'];
            $totals[$verdict] = abs((int) $row['total']);
        }

        return $totals;
    }

    /**
     * What the envelopes of a set of categories consumed each day of a
     * half-open period: spent and committed debits, as positive cents per
     * Y-m-d. Same scope as {@see sumByStatusForCategoriesBetween} — transfers
     * and spends attributed to a one-off envelope are out. A day with nothing
     * is absent.
     *
     * @param list<Ulid> $categoryIds
     *
     * @return array<string, int>
     */
    public function sumConsumedPerDayForCategoriesBetween(User $user, array $categoryIds, \DateTimeImmutable $from, \DateTimeImmutable $until): array
    {
        if ([] === $categoryIds) {
            return [];
        }

        $rows = $this->createQueryBuilder('t')
            ->select('t.bookedAt AS bookedAt, '.self::OWN_AMOUNT.' AS amountCents')
            ->andWhere('t.user = :user')
            ->andWhere('t.transferKind = :noTransfer')
            ->andWhere('t.category IN (:categories)')
            ->andWhere('t.oneOffEnvelope IS NULL')
            ->andWhere('t.amountCents < 0')
            ->andWhere('t.status IN (:consumed)')
            ->andWhere('t.bookedAt >= :from')
            ->andWhere('t.bookedAt < :until')
            ->setParameter('user', $user->getId(), 'ulid')
            ->setParameter('noTransfer', TransferKind::None->value)
            ->setParameter('categories', array_map(static fn (Ulid $id): string => $id->toRfc4122(), $categoryIds), ArrayParameterType::STRING)
            ->setParameter('consumed', [TransactionStatus::Spent->value, TransactionStatus::Committed->value])
            ->setParameter('from', $from)
            ->setParameter('until', $until)
            ->getQuery()
            ->getResult();

        $perDay = [];
        foreach ($rows as $row) {
            $day = $row['bookedAt']->format('Y-m-d');
            $perDay[$day] = ($perDay[$day] ?? 0) + abs((int) $row['amountCents']);
        }

        return $perDay;
    }

    /**
     * Debits attributed to the user's one-off envelopes, whenever booked, per
     * envelope id then per status, as positive cents. An envelope nothing was
     * attributed to is absent.
     *
     * @return array<string, array<string, int>>
     */
    public function sumByStatusForOneOffEnvelopes(User $user): array
    {
        $rows = $this->createQueryBuilder('t')
            ->select('IDENTITY(t.oneOffEnvelope) AS envelope, t.status AS status, SUM('.self::OWN_AMOUNT.') AS total')
            ->andWhere('t.user = :user')
            ->andWhere('t.oneOffEnvelope IS NOT NULL')
            ->andWhere('t.transferKind = :noTransfer')
            ->andWhere('t.amountCents < 0')
            ->setParameter('user', $user->getId(), 'ulid')
            ->setParameter('noTransfer', TransferKind::None->value)
            ->groupBy('t.oneOffEnvelope, t.status')
            ->getQuery()
            ->getResult();

        $totals = [];
        foreach ($rows as $row) {
            $envelopeId = (string) Ulid::fromString((string) $row['envelope']);
            if (!isset($totals[$envelopeId])) {
                foreach (TransactionStatus::cases() as $status) {
                    $totals[$envelopeId][$status->value] = 0;
                }
            }
            $status = $row['status'] instanceof TransactionStatus ? $row['status']->value : (string) $row['status'];
            $totals[$envelopeId][$status] = abs((int) $row['total']);
        }

        return $totals;
    }

    /** @return Transaction[] the lines attributed to a one-off envelope */
    public function findByOneOffEnvelope(Envelope $envelope): array
    {
        return $this->findBy(['oneOffEnvelope' => $envelope]);
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
     * The lines waiting to be sorted by hand: no category — or a transfer with
     * a contact not told apart yet (MAG-453) — and no transfer or rejection
     * marking, whoever emptied the category — a line whose category
     * was removed by hand still has none (the « À trier » pill says the same).
     * Newest first, or oldest first when the review walks the pile.
     *
     * @return array{transactions: Transaction[], total: int}
     */
    public function findToSortForUser(User $user, int $limit, bool $oldestFirst = false): array
    {
        $total = $this->countToSortForUser($user);
        $direction = $oldestFirst ? 'ASC' : 'DESC';

        /** @var Transaction[] $transactions */
        $transactions = $this->toSortQuery($user)
            ->orderBy('t.bookedAt', $direction)
            ->addOrderBy('t.id', $direction)
            ->setMaxResults($limit)
            ->getQuery()
            ->getResult();

        return ['transactions' => $transactions, 'total' => $total];
    }

    /** What the pile weighs: the sum of the amounts, whatever their sign, in cents. */
    public function sumToSortForUser(User $user): int
    {
        return (int) $this->toSortQuery($user)
            ->select('COALESCE(SUM(ABS(t.amountCents)), 0)')
            ->getQuery()
            ->getSingleScalarResult();
    }

    /**
     * The lines of a period an operation recognised — a series or a rule set
     * their qualification, the owner did not — oldest first. Transfers are
     * left out: they are neutral, there is nothing to check on them.
     *
     * @return Transaction[]
     */
    public function findRecognisedBetween(User $user, \DateTimeImmutable $from, \DateTimeImmutable $until, int $limit): array
    {
        return $this->createQueryBuilder('t')
            ->andWhere('t.user = :user')
            ->andWhere('t.transferKind = :none')
            ->andWhere('t.categorySource IN (:operations)')
            ->andWhere('t.bookedAt >= :from')
            ->andWhere('t.bookedAt < :until')
            ->setParameter('user', $user->getId(), 'ulid')
            ->setParameter('none', TransferKind::None->value)
            ->setParameter('operations', [CategorySource::Rule->value, CategorySource::Series->value])
            ->setParameter('from', $from)
            ->setParameter('until', $until)
            ->orderBy('t.bookedAt', 'ASC')
            ->addOrderBy('t.id', 'ASC')
            ->setMaxResults($limit)
            ->getQuery()
            ->getResult();
    }

    /** How many lines {@see findToSortForUser} stands for, without loading them. */
    public function countToSortForUser(User $user): int
    {
        return (int) $this->toSortQuery($user)
            ->select('COUNT(t.id)')
            ->getQuery()
            ->getSingleScalarResult();
    }

    /**
     * No category, or — since MAG-453 — a transfer linked to a contact that
     * the owner has not told apart yet (income, repayment, new debt…). A
     * shared spend is told apart already: the owner said who owes what.
     */
    private function toSortQuery(User $user): QueryBuilder
    {
        return $this->createQueryBuilder('t')
            ->andWhere('t.user = :user')
            ->andWhere('(t.category IS NULL OR (t.contact IS NOT NULL AND t.peerKind IS NULL AND t.myShareCents IS NULL AND t.amountCents != 0))')
            ->andWhere('t.transferKind = :none')
            ->setParameter('user', $user->getId(), 'ulid')
            ->setParameter('none', TransferKind::None->value);
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
            // A shared spend was paid: its créance would be left for nothing (MAG-451).
            ->andWhere('t.myShareCents IS NULL')
            // Nor one that gives back a dette: its allocations would repay nothing (MAG-453).
            ->andWhere('t.peerAllocatedCents = 0')
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
     * and committed debits, without the exceptional ones, the repayments
     * (counted on their own by the debt timeline) and what is put aside
     * (saving, investment) — all read off the transaction's qualification.
     */
    public function sumConsumedBetween(User $user, \DateTimeImmutable $from, \DateTimeImmutable $until): int
    {
        $total = $this->createQueryBuilder('t')
            ->select('SUM('.self::OWN_AMOUNT.')')
            ->andWhere('t.user = :user')
            ->andWhere('t.transferKind = :noTransfer')
            ->andWhere('t.amountCents < 0')
            ->andWhere('t.status IN (:consumed)')
            ->andWhere('t.isExceptional = false')
            ->andWhere('t.qualification.kind NOT IN (:outsideLifestyle)')
            ->andWhere('t.bookedAt >= :from')
            ->andWhere('t.bookedAt < :until')
            ->setParameter('user', $user->getId(), 'ulid')
            ->setParameter('noTransfer', TransferKind::None->value)
            ->setParameter('consumed', [TransactionStatus::Spent->value, TransactionStatus::Committed->value])
            ->setParameter('outsideLifestyle', [QualificationKind::Repayment->value, QualificationKind::Saving->value, QualificationKind::Investment->value])
            ->setParameter('from', $from)
            ->setParameter('until', $until)
            ->getQuery()
            ->getSingleScalarResult();

        return abs((int) $total);
    }

    /**
     * What was put aside over a half-open period, as positive cents: the debits
     * the transaction itself qualifies as saving or investment. A transfer to
     * a savings account counts once (its debit leg); rejected payments and
     * advances do not.
     */
    public function sumSavedBetween(User $user, \DateTimeImmutable $from, \DateTimeImmutable $until): int
    {
        $total = $this->createQueryBuilder('t')
            ->select('SUM(t.amountCents)')
            ->andWhere('t.user = :user')
            ->andWhere('t.transferKind IN (:kinds)')
            ->andWhere('t.amountCents < 0')
            ->andWhere('t.status IN (:consumed)')
            ->andWhere('t.qualification.kind IN (:saved)')
            ->andWhere('t.bookedAt >= :from')
            ->andWhere('t.bookedAt < :until')
            ->setParameter('user', $user->getId(), 'ulid')
            ->setParameter('kinds', [TransferKind::None->value, TransferKind::Internal->value])
            ->setParameter('consumed', [TransactionStatus::Spent->value, TransactionStatus::Committed->value])
            ->setParameter('saved', [QualificationKind::Saving->value, QualificationKind::Investment->value])
            ->setParameter('from', $from)
            ->setParameter('until', $until)
            ->getQuery()
            ->getSingleScalarResult();

        return abs((int) $total);
    }

    /**
     * The lines of a half-open period nobody qualified yet — the « À qualifier »
     * pile restricted to the month — with what they weigh, whatever their sign.
     *
     * @return array{count: int, cents: int}
     */
    public function summariseToSortBetween(User $user, \DateTimeImmutable $from, \DateTimeImmutable $until): array
    {
        /** @var array{count: int|string, cents: int|string|null} $row */
        $row = $this->toSortQuery($user)
            ->select('COUNT(t.id) AS count', 'COALESCE(SUM(ABS(t.amountCents)), 0) AS cents')
            ->andWhere('t.bookedAt >= :from')
            ->andWhere('t.bookedAt < :until')
            ->setParameter('from', $from)
            ->setParameter('until', $until)
            ->getQuery()
            ->getSingleResult();

        return ['count' => (int) $row['count'], 'cents' => (int) $row['cents']];
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
     * The movements already entered but not yet booked on the open checking
     * accounts — planned and committed, debits and credits, transfers
     * included — over a half-open period, in date order: all a treasury
     * projection may add to today's balance, and nothing it would have to guess.
     *
     * @return Transaction[]
     */
    public function findForecastMovementsBetween(User $user, \DateTimeImmutable $from, \DateTimeImmutable $until): array
    {
        return $this->createQueryBuilder('t')
            ->innerJoin('t.account', 'a')
            ->andWhere('t.user = :user')
            ->andWhere('a.user = :user')
            ->andWhere('a.type = :checking')
            ->andWhere('a.closedAt IS NULL')
            ->andWhere('t.status IN (:forecast)')
            ->andWhere('t.bookedAt >= :from')
            ->andWhere('t.bookedAt < :until')
            ->setParameter('user', $user->getId(), 'ulid')
            ->setParameter('checking', AccountType::Checking->value)
            ->setParameter('forecast', [TransactionStatus::Planned->value, TransactionStatus::Committed->value])
            ->setParameter('from', $from)
            ->setParameter('until', $until)
            ->orderBy('t.bookedAt', 'ASC')
            ->addOrderBy('t.id', 'ASC')
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
            ->select('IDENTITY(t.category) AS categoryId, c.name AS categoryName, t.amountCents - t.peerAllocatedCents AS amountCents, '.self::DEBT_AMOUNT.' AS debtCents')
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
            ->getQuery()
            ->getResult();

        $byCategory = [];
        foreach ($rows as $row) {
            $categoryId = (string) $row['categoryId'];
            $byCategory[$categoryId] ??= ['categoryId' => $categoryId, 'categoryName' => (string) $row['categoryName'], 'incomeCents' => 0];
            $byCategory[$categoryId]['incomeCents'] += (int) $row['amountCents'] - (int) $row['debtCents'];
        }
        $income = array_values($byCategory);

        usort($income, static fn (array $a, array $b) => $b['incomeCents'] <=> $a['incomeCents']);

        return $income;
    }

    /**
     * Debits of a period that are up for review: everything the transaction
     * does not qualify as mandatory, nor as a loan or a repayment —
     * uncategorized spends included, those are precisely the ones worth a
     * second look. Biggest first.
     *
     * @return Transaction[]
     */
    public function findReviewableBetween(User $user, \DateTimeImmutable $from, \DateTimeImmutable $until): array
    {
        return $this->createQueryBuilder('t')
            ->andWhere('t.user = :user')
            ->andWhere('t.transferKind = :noTransfer')
            ->andWhere('t.amountCents < 0')
            ->andWhere('t.status IN (:consumed)')
            ->andWhere('t.bookedAt >= :from')
            ->andWhere('t.bookedAt < :until')
            ->andWhere('t.qualification.mandatory = false')
            ->andWhere('t.qualification.kind NOT IN (:notReviewable)')
            ->setParameter('user', $user->getId(), 'ulid')
            ->setParameter('noTransfer', TransferKind::None->value)
            ->setParameter('consumed', [TransactionStatus::Spent->value, TransactionStatus::Committed->value])
            ->setParameter('from', $from)
            ->setParameter('until', $until)
            ->setParameter('notReviewable', [QualificationKind::Repayment->value, QualificationKind::Loan->value])
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
        /** @var array<int, array{bookedAt: \DateTimeImmutable, amountCents: int, allocatedCents: int, debtCents: int|string}> $rows */
        $rows = $this->createQueryBuilder('t')
            ->select('t.bookedAt AS bookedAt, t.amountCents AS amountCents, t.peerAllocatedCents AS allocatedCents, '.self::DEBT_AMOUNT.' AS debtCents')
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

            // What gives back a debt between people is neither an income nor an expense (MAG-453).
            if ($row['amountCents'] >= 0) {
                // What is owed back on a received transfer is no income.
                $flows[$month]['incomeCents'] += $row['amountCents'] - (int) $row['allocatedCents'] - (int) $row['debtCents'];
            } else {
                $flows[$month]['expenseCents'] += abs($row['amountCents']) - (int) $row['allocatedCents'];
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
            ->select('IDENTITY(t.category) AS categoryId, c.name AS categoryName, SUM('.self::OWN_AMOUNT.') AS total')
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

    /**
     * The currencies this account's movements are in, the most frequent first.
     *
     * @return list<string>
     */
    public function findCurrenciesOfAccount(Account $account): array
    {
        $rows = $this->createQueryBuilder('t')
            ->select('t.currency AS currency')
            ->andWhere('t.account = :account')
            ->setParameter('account', $account->getId(), 'ulid')
            ->groupBy('t.currency')
            ->orderBy('COUNT(t.id)', 'DESC')
            ->addOrderBy('t.currency', 'ASC')
            ->getQuery()
            ->getScalarResult();

        return array_values(array_map(static fn (array $row): string => (string) $row['currency'], $rows));
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
     * The lines whose contact may change when a counterparty is linked or
     * removed: those attached to someone, and those that could be.
     *
     * @return Transaction[]
     */
    public function findContactCandidates(User $user): array
    {
        return $this->createQueryBuilder('t')
            ->andWhere('t.user = :user')
            ->andWhere('t.transferKind = :none OR t.contact IS NOT NULL')
            ->setParameter('user', $user->getId(), 'ulid')
            ->setParameter('none', TransferKind::None->value)
            ->getQuery()
            ->getResult();
    }

    /**
     * The lines attached to these contacts, newest first.
     *
     * @param Contact[] $contacts
     *
     * @return Transaction[]
     */
    public function findByContacts(array $contacts): array
    {
        if ([] === $contacts) {
            return [];
        }

        return $this->createQueryBuilder('t')
            ->andWhere('t.contact IN (:contacts)')
            ->setParameter('contacts', array_map(static fn (Contact $c): string => $c->getId()->toRfc4122(), $contacts), ArrayParameterType::STRING)
            ->orderBy('t.bookedAt', 'DESC')
            ->getQuery()
            ->getResult();
    }

    /**
     * The lines no contact claims yet, that a counterparty could: neither a
     * neutral movement nor attached to someone.
     *
     * @return Transaction[]
     */
    public function findWithoutContact(User $user): array
    {
        return $this->createQueryBuilder('t')
            ->andWhere('t.user = :user')
            ->andWhere('t.transferKind = :none')
            ->andWhere('t.contact IS NULL')
            ->setParameter('user', $user->getId(), 'ulid')
            ->setParameter('none', TransferKind::None->value)
            ->orderBy('t.bookedAt', 'DESC')
            ->getQuery()
            ->getResult();
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

    /**
     * The lines of one user that may still be attached to a recurring
     * operation, oldest first: unattached, never judged by hand, not a neutral
     * movement, not waiting for arbitration. Whose series they are is the use
     * case's call. One query for the whole catch-up.
     *
     * @return Transaction[]
     */
    public function findRecurringCandidates(User $user, ?\DateTimeImmutable $since = null): array
    {
        $qb = $this->createQueryBuilder('t')
            ->andWhere('t.user = :user')
            ->andWhere('t.recurringOperation IS NULL')
            ->andWhere('t.recurringSource = :auto')
            ->andWhere('t.transferKind = :none')
            ->andWhere('t.status != :toArbitrate')
            ->setParameter('user', $user->getId(), 'ulid')
            ->setParameter('auto', RecurringLinkSource::Auto->value)
            ->setParameter('none', TransferKind::None->value)
            ->setParameter('toArbitrate', TransactionStatus::ToArbitrate->value)
            ->orderBy('t.bookedAt', 'ASC')
            ->addOrderBy('t.id', 'ASC');

        if (null !== $since) {
            $qb->andWhere('t.bookedAt >= :since')->setParameter('since', $since);
        }

        return $qb->getQuery()->getResult();
    }

    /**
     * The occurrences of one user's series already settled, as
     * `seriesId|Y-m-d` => the transaction that settles it. One query.
     *
     * @return array<string, string>
     */
    public function findTakenOccurrences(User $user): array
    {
        $rows = $this->createQueryBuilder('t')
            ->select('IDENTITY(t.recurringOperation) AS operationId', 't.recurringOccurrenceOn AS occurrenceOn', 't.id AS transactionId')
            ->andWhere('t.user = :user')
            ->andWhere('t.recurringOperation IS NOT NULL')
            ->setParameter('user', $user->getId(), 'ulid')
            ->getQuery()
            ->getArrayResult();

        $taken = [];
        foreach ($rows as $row) {
            if (!$row['occurrenceOn'] instanceof \DateTimeInterface) {
                continue;
            }
            $taken[self::occurrenceKey(self::ulid($row['operationId']), $row['occurrenceOn'])] = self::ulid($row['transactionId']);
        }

        return $taken;
    }

    /**
     * Every line attached to one of a user's series, oldest occurrence first,
     * as plain rows: what the recurring overview reads its history from, in
     * one query instead of one per series.
     *
     * @return list<array{operationId: string, occurrenceOn: string, bookedAt: string, amountCents: int, transactionId: string}>
     */
    public function findAttachmentRows(User $user): array
    {
        $rows = $this->createQueryBuilder('t')
            ->select('IDENTITY(t.recurringOperation) AS operationId', 't.recurringOccurrenceOn AS occurrenceOn', 't.bookedAt AS bookedAt', 't.amountCents AS amountCents', 't.id AS transactionId')
            ->andWhere('t.user = :user')
            ->andWhere('t.recurringOperation IS NOT NULL')
            ->andWhere('t.recurringOccurrenceOn IS NOT NULL')
            ->setParameter('user', $user->getId(), 'ulid')
            ->orderBy('t.recurringOccurrenceOn', 'ASC')
            ->addOrderBy('t.id', 'ASC')
            ->getQuery()
            ->getArrayResult();

        return array_map(static fn (array $row) => [
            'operationId' => self::ulid($row['operationId']),
            'occurrenceOn' => $row['occurrenceOn']->format('Y-m-d'),
            'bookedAt' => $row['bookedAt']->format('Y-m-d'),
            'amountCents' => (int) $row['amountCents'],
            'transactionId' => self::ulid($row['transactionId']),
        ], $rows);
    }

    /** The transaction that settles this occurrence, if one does. */
    public function findAttachedToOccurrence(RecurringOperation $operation, \DateTimeImmutable $occurrenceOn): ?Transaction
    {
        return $this->createQueryBuilder('t')
            ->andWhere('t.recurringOperation = :operation')
            ->andWhere('t.recurringOccurrenceOn = :occurrenceOn')
            ->setParameter('operation', $operation->getId(), 'ulid')
            ->setParameter('occurrenceOn', $occurrenceOn->setTime(0, 0), 'date_immutable')
            ->setMaxResults(1)
            ->getQuery()
            ->getOneOrNullResult();
    }

    /**
     * The latest lines attached to a series, latest occurrence first: what a
     * measured reference amount is recalibrated on.
     *
     * @return Transaction[]
     */
    public function findLastAttached(RecurringOperation $operation, int $limit): array
    {
        return $this->createQueryBuilder('t')
            ->andWhere('t.recurringOperation = :operation')
            ->setParameter('operation', $operation->getId(), 'ulid')
            ->orderBy('t.recurringOccurrenceOn', 'DESC')
            ->addOrderBy('t.id', 'DESC')
            ->setMaxResults($limit)
            ->getQuery()
            ->getResult();
    }

    /**
     * What a series was really paid, latest occurrence first: its attached
     * lines in the direction it expects, without the neutral movements
     * (internal transfers, rejected payments) nor the lines flagged
     * exceptional. What the reference salary averages.
     *
     * @return list<int> amounts in signed cents
     */
    public function findLastPaidAmounts(RecurringOperation $operation, int $limit): array
    {
        $rows = $this->createQueryBuilder('t')
            // What gives back a debt between people was not paid by the series (MAG-453), whichever its direction.
            ->select('CASE WHEN t.amountCents < 0 THEN t.amountCents + t.peerAllocatedCents ELSE t.amountCents - t.peerAllocatedCents END AS amountCents, '.self::DEBT_AMOUNT.' AS debtCents')
            ->andWhere('t.recurringOperation = :operation')
            ->andWhere('t.transferKind = :none')
            ->andWhere('t.isExceptional = false')
            ->andWhere($operation->getReferenceAmountCents() < 0 ? 't.amountCents < 0' : 't.amountCents > 0')
            ->setParameter('operation', $operation->getId(), 'ulid')
            ->setParameter('none', TransferKind::None->value)
            ->orderBy('t.recurringOccurrenceOn', 'DESC')
            ->addOrderBy('t.id', 'DESC')
            ->setMaxResults($limit)
            ->getQuery()
            ->getArrayResult();

        return array_map(static fn (array $row) => (int) $row['amountCents'] - (int) $row['debtCents'], $rows);
    }

    /** @return Transaction[] every line attached to a series */
    public function findAttachedTo(RecurringOperation $operation): array
    {
        return $this->findBy(['recurringOperation' => $operation], ['recurringOccurrenceOn' => 'ASC', 'id' => 'ASC']);
    }

    /**
     * The lines an operation qualified and that are still its own: a line
     * qualified by hand since then names no operation any more, and a line
     * taken out of its series keeps what it inherited but no longer follows it.
     *
     * @return Transaction[]
     */
    public function findQualifiedBy(RecurringOperation $operation): array
    {
        return $this->findBy(
            $operation->hasRecurrence()
                ? ['qualifiedBySeries' => $operation, 'recurringOperation' => $operation, 'categorySource' => CategorySource::Series]
                : ['qualifiedByRule' => $operation, 'categorySource' => CategorySource::Rule],
            ['bookedAt' => 'ASC', 'id' => 'ASC'],
        );
    }

    /**
     * The credits filed under the standard « Salaire » (or a category under
     * it) booked in a span, both days included, oldest first: what closes a
     * salary delay. Neutral movements stay out; a bonus is in « Primes », not
     * here, and a rente is left to the caller, which reads the category.
     *
     * @return Transaction[]
     */
    public function findSalaryCreditsBetween(User $user, \DateTimeImmutable $from, \DateTimeImmutable $until): array
    {
        return $this->createQueryBuilder('t')
            ->innerJoin('t.category', 'c')
            ->leftJoin('c.parent', 'p')
            ->andWhere('t.user = :user')
            ->andWhere('c.user = :user')
            ->andWhere('t.amountCents > 0')
            ->andWhere('t.transferKind = :none')
            ->andWhere('t.status IN (:consumed)')
            ->andWhere('c.standardKey = :salary OR p.standardKey = :salary')
            ->andWhere('t.bookedAt >= :from')
            ->andWhere('t.bookedAt <= :until')
            ->setParameter('user', $user->getId(), 'ulid')
            ->setParameter('none', TransferKind::None->value)
            ->setParameter('consumed', [TransactionStatus::Spent->value, TransactionStatus::Committed->value])
            ->setParameter('salary', StandardCategories::SALARY)
            ->setParameter('from', $from)
            ->setParameter('until', $until)
            ->orderBy('t.bookedAt', 'ASC')
            ->addOrderBy('t.id', 'ASC')
            ->getQuery()
            ->getResult();
    }

    /**
     * The paired lines of one kind decided by the detection, debit legs only,
     * oldest first — one row per movement.
     *
     * @param \DateTimeImmutable|null $since how far back to look, or null for everything
     *
     * @return Transaction[]
     */
    public function findAutoPairedDebits(User $user, TransferKind $kind, ?\DateTimeImmutable $since = null): array
    {
        $qb = $this->createQueryBuilder('t')
            ->innerJoin('t.account', 'a')
            ->andWhere('t.user = :user')
            ->andWhere('a.user = :user')
            ->andWhere('t.amountCents < 0')
            ->andWhere('t.counterpart IS NOT NULL')
            ->andWhere('t.transferKind = :kind')
            ->andWhere('t.transferSource = :auto')
            ->setParameter('user', $user->getId(), 'ulid')
            ->setParameter('kind', $kind->value)
            ->setParameter('auto', TransferSource::Auto->value)
            ->orderBy('t.bookedAt', 'ASC')
            ->addOrderBy('t.id', 'ASC');

        if (null !== $since) {
            $qb->andWhere('t.bookedAt >= :since')->setParameter('since', $since);
        }

        return $qb->getQuery()->getResult();
    }

    /**
     * The savings advances of one user — the debit leg, on the source account —
     * oldest first.
     *
     * @return Transaction[]
     */
    public function findAdvanceDebits(User $user): array
    {
        return $this->createQueryBuilder('t')
            ->innerJoin('t.account', 'a')
            ->andWhere('t.user = :user')
            ->andWhere('a.user = :user')
            ->andWhere('t.amountCents < 0')
            ->andWhere('t.transferKind = :advance')
            ->setParameter('user', $user->getId(), 'ulid')
            ->setParameter('advance', TransferKind::Advance->value)
            ->orderBy('t.bookedAt', 'ASC')
            ->addOrderBy('t.id', 'ASC')
            ->getQuery()
            ->getResult();
    }

    /**
     * What has been given back on each advance: the debit legs pointing at it
     * with `repays`, as positive cents, keyed by the advance's id.
     *
     * @param Transaction[] $advances
     *
     * @return array<string, int>
     */
    public function sumRepaidByAdvance(User $user, array $advances): array
    {
        if ([] === $advances) {
            return [];
        }

        $rows = $this->createQueryBuilder('t')
            ->select('IDENTITY(t.repays) AS advanceId, SUM(t.amountCents) AS total')
            ->andWhere('t.user = :user')
            ->andWhere('t.amountCents < 0')
            ->andWhere('t.repays IN (:advances)')
            ->setParameter('user', $user->getId(), 'ulid')
            ->setParameter('advances', array_map(static fn (Transaction $t): string => $t->getId()->toRfc4122(), $advances), ArrayParameterType::STRING)
            ->groupBy('advanceId')
            ->getQuery()
            ->getResult();

        $repaid = [];
        foreach ($rows as $row) {
            $repaid[self::ulid($row['advanceId'])] = -(int) $row['total'];
        }

        return $repaid;
    }

    /** A scalar id as Doctrine returns it — a Ulid, or the database's UUID form — in the base 32 every caller compares. */
    private static function ulid(mixed $id): string
    {
        return (string) ($id instanceof Ulid ? $id : Ulid::fromString((string) $id));
    }

    /** How an occurrence is keyed wherever two transactions must not share it. */
    public static function occurrenceKey(string $operationId, \DateTimeInterface $occurrenceOn): string
    {
        return $operationId.'|'.$occurrenceOn->format('Y-m-d');
    }
}
