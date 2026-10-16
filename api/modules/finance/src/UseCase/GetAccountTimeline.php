<?php

declare(strict_types=1);

namespace Maggie\Finance\UseCase;

use Maggie\Finance\Entity\Account;
use Maggie\Finance\Entity\Transaction;
use Maggie\Finance\Repository\TransactionRepository;

/**
 * One account over one month, day by day (MAG-422): its lines with their real
 * status, the account on the other side of an internal transfer, the price
 * rise of a recurring operation on the line where it began, and the due dates
 * of recurring operations that never came.
 *
 * A pure read. The recurring side is what the recurring overview already
 * computes (MAG-306): nothing is derived twice. Rejected payments are not
 * here, they have their own list (MAG-375).
 */
class GetAccountTimeline
{
    public function __construct(
        private readonly TransactionRepository $transactionRepository,
        private readonly GetRecurringOperations $getRecurringOperations,
    ) {
    }

    /**
     * Days newest first, only those with a line or a missed due date.
     * `previousMonth` is the month of the latest line before this one, so a
     * client can page back without walking through empty months.
     *
     * @return array{month: string, previousMonth: ?string, account: array<string, mixed>, days: list<array<string, mixed>>}
     */
    public function execute(Account $account, \DateTimeImmutable $month, ?\DateTimeImmutable $today = null): array
    {
        $first = $month->modify('first day of this month')->setTime(0, 0);
        $last = $first->modify('last day of this month');
        $accountId = (string) $account->getId();

        $rises = [];
        $missed = [];
        foreach ($this->getRecurringOperations->execute($account->getUser(), $today)['operations'] as $operation) {
            if ($operation['accountId'] !== $accountId) {
                continue;
            }

            $change = $operation['priceChange'];
            if (null !== $change && $change['percent'] > 0) {
                $rises[$operation['id']] = $change;
            }

            $gap = $operation['missedOccurrence'];
            if (null !== $gap && $gap['occurrenceOn'] >= $first->format('Y-m-d') && $gap['occurrenceOn'] <= $last->format('Y-m-d')) {
                $missed[$gap['occurrenceOn']][] = [
                    'recurringOperationId' => $operation['id'],
                    'label' => $operation['label'],
                    'expectedOn' => $gap['occurrenceOn'],
                    'amountCents' => $operation['referenceAmountCents'],
                ];
            }
        }

        /** @var array<string, list<array<string, mixed>>> $lines */
        $lines = [];
        foreach ($this->transactionRepository->findByAccountBetween($account, $first, $last) as $transaction) {
            $lines[$transaction->getBookedAt()->format('Y-m-d')][] = self::line($transaction, $rises);
        }

        $dates = array_unique([...array_keys($lines), ...array_keys($missed)]);
        rsort($dates);

        $days = array_map(static fn (string $date) => [
            'date' => $date,
            'transactions' => $lines[$date] ?? [],
            'missedOccurrences' => $missed[$date] ?? [],
        ], $dates);

        return [
            'month' => $first->format('Y-m'),
            'previousMonth' => $this->transactionRepository->findLatestBookingDayBefore($account, $first)?->format('Y-m'),
            'account' => [
                'id' => $accountId,
                'name' => $account->getName(),
                'bank' => $account->effectiveBank(),
                'type' => $account->getType()->value,
                'currency' => $account->getCurrency(),
                'balanceCents' => $account->getBalanceCents(),
                'isCushion' => $account->isCushion(),
                'isManual' => null === $account->getBankConnection() && null === $account->getExternalKey(),
            ],
            'days' => $days,
        ];
    }

    /**
     * @param array<string, array{previousCents: int, currentCents: int, percent: int, since: string}> $rises by series id
     *
     * @return array<string, mixed>
     */
    private static function line(Transaction $transaction, array $rises): array
    {
        $kind = $transaction->getTransferKind();
        $other = $kind->isBetweenAccounts() ? $transaction->getCounterpart()?->getAccount() : null;
        $series = $transaction->getRecurringOperation();
        $seriesId = null === $series ? null : (string) $series->getId();

        $rise = null === $seriesId ? null : ($rises[$seriesId] ?? null);
        $beginsHere = null !== $rise && $transaction->getRecurringOccurrenceOn()?->format('Y-m-d') === $rise['since'];

        return [
            'id' => (string) $transaction->getId(),
            'label' => $transaction->getLabel(),
            'counterpartyName' => $transaction->getCounterpartyName(),
            'amountCents' => $transaction->getAmountCents(),
            'currency' => $transaction->getCurrency(),
            'status' => $transaction->getStatus()->value,
            'categoryId' => null === $transaction->getCategory() ? null : (string) $transaction->getCategory()->getId(),
            'transferKind' => $kind->value,
            'counterpartAccount' => null === $other ? null : ['id' => (string) $other->getId(), 'name' => $other->getName()],
            'recurringOperationId' => $seriesId,
            'recurringIncrease' => $beginsHere ? ['percent' => $rise['percent'], 'previousCents' => $rise['previousCents']] : null,
        ];
    }
}
