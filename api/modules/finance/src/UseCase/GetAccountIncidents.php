<?php

declare(strict_types=1);

namespace Maggie\Finance\UseCase;

use Maggie\Finance\Entity\Account;
use Maggie\Finance\Entity\Transaction;
use Maggie\Finance\Import\MerchantExtractor;
use Maggie\Finance\Repository\TransactionRepository;

/**
 * The payments the bank rejected on an account, one line per rejection (MAG-375).
 *
 * A rejection is two lines of the statement, the debit and the credit that
 * gives it back, paired by `counterpart`. The owner reads it as one incident.
 * A rejected line with no other leg still shows, with the missing side null,
 * rather than vanish from both lists.
 */
class GetAccountIncidents
{
    public function __construct(private readonly TransactionRepository $transactionRepository)
    {
    }

    /**
     * Newest first, by the day the debit was booked.
     *
     * @return list<array{debitId: ?string, creditId: ?string, bookedAt: string, rejectedAt: ?string, counterpartyName: string, amountCents: int, kind: string}>
     */
    public function execute(Account $account): array
    {
        /** @var array<string, array{debit: ?Transaction, credit: ?Transaction}> $pairs */
        $pairs = [];

        foreach ($this->transactionRepository->findRejectedByAccount($account) as $line) {
            $other = $line->getCounterpart();

            if ($line->getAmountCents() < 0) {
                $debit = $line;
                $credit = $other;
            } else {
                $debit = null !== $other && $other->getAmountCents() < 0 ? $other : null;
                $credit = $line;
            }

            $key = ($debit?->getId() ?? '-').'|'.($credit?->getId() ?? '-');
            $pairs[$key] = ['debit' => $debit, 'credit' => $credit];
        }

        $incidents = [];
        foreach ($pairs as ['debit' => $debit, 'credit' => $credit]) {
            $first = $debit ?? $credit;
            \assert(null !== $first);

            $incidents[] = [
                'debitId' => null === $debit ? null : (string) $debit->getId(),
                'creditId' => null === $credit ? null : (string) $credit->getId(),
                'bookedAt' => $first->getBookedAt()->format('Y-m-d'),
                'rejectedAt' => $credit?->getBookedAt()->format('Y-m-d'),
                'counterpartyName' => self::payee($debit, $credit),
                'amountCents' => abs($first->getAmountCents()),
                'kind' => DetectRejections::paymentKind($debit, $credit)->value,
            ];
        }

        usort($incidents, static fn (array $a, array $b) => [$b['bookedAt'], $b['debitId'] ?? '', $b['creditId'] ?? '']
            <=> [$a['bookedAt'], $a['debitId'] ?? '', $a['creditId'] ?? '']);

        return $incidents;
    }

    /** Who was paid, as the notification of the rejection names them. */
    private static function payee(?Transaction $debit, ?Transaction $credit): string
    {
        foreach ([$debit, $credit] as $line) {
            if (null !== $line && null !== $line->getCounterpartyName()) {
                return $line->getCounterpartyName();
            }
        }
        foreach ([$debit, $credit] as $line) {
            $name = null === $line ? null : MerchantExtractor::extract($line->getLabel());
            if (null !== $name) {
                return $name;
            }
        }

        return ($debit ?? $credit)?->getLabel() ?? '';
    }
}
