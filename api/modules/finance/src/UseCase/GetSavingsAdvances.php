<?php

declare(strict_types=1);

namespace Maggie\Finance\UseCase;

use Maggie\Core\Entity\User;
use Maggie\Finance\Repository\TransactionRepository;

/**
 * How much the owner took from each savings source while the salary was late,
 * and how much is left to give back.
 *
 * Nothing is stored: an advance is the debit leg of a transfer marked
 * `advance`, and what was given back is the sum of the repayments pointing at
 * it with `repays`. A settled advance stays in the list, closed, and leaves
 * the totals. An advance taken on a cushion account already lowered its
 * balance: nothing here takes it off the cushion a second time.
 *
 * Its fields are read by the monthly review and the mobile home: keep them.
 */
class GetSavingsAdvances
{
    public function __construct(
        private readonly TransactionRepository $transactionRepository,
    ) {
    }

    /**
     * @return array{
     *     advances: list<array{id: string, sourceAccountId: string, sourceAccountName: string, amountCents: int, openedOn: string, repaidCents: int, outstandingCents: int, isClosed: bool}>,
     *     bySource: list<array{sourceAccountId: string, sourceAccountName: string, outstandingCents: int, openCount: int}>,
     *     totalOutstandingCents: int
     * }
     */
    public function execute(User $user): array
    {
        $debits = $this->transactionRepository->findAdvanceDebits($user);
        $repaid = $this->transactionRepository->sumRepaidByAdvance($user, $debits);

        $advances = [];
        $bySource = [];

        foreach ($debits as $debit) {
            $id = (string) $debit->getId();
            $account = $debit->getAccount();
            $accountId = (string) $account->getId();
            $amount = -$debit->getAmountCents();
            $repaidCents = $repaid[$id] ?? 0;
            $outstanding = max(0, $amount - $repaidCents);

            $advances[] = [
                'id' => $id,
                'sourceAccountId' => $accountId,
                'sourceAccountName' => $account->getName(),
                'amountCents' => $amount,
                'openedOn' => $debit->getBookedAt()->format('Y-m-d'),
                'repaidCents' => $repaidCents,
                'outstandingCents' => $outstanding,
                'isClosed' => 0 === $outstanding,
            ];

            if (0 === $outstanding) {
                continue;
            }

            $bySource[$accountId] ??= [
                'sourceAccountId' => $accountId,
                'sourceAccountName' => $account->getName(),
                'outstandingCents' => 0,
                'openCount' => 0,
            ];
            $bySource[$accountId]['outstandingCents'] += $outstanding;
            ++$bySource[$accountId]['openCount'];
        }

        $bySource = array_values($bySource);
        usort($bySource, static fn (array $a, array $b) => [$b['outstandingCents'], $a['sourceAccountName']]
            <=> [$a['outstandingCents'], $b['sourceAccountName']]);

        return [
            'advances' => $advances,
            'bySource' => $bySource,
            'totalOutstandingCents' => array_sum(array_column($bySource, 'outstandingCents')),
        ];
    }
}
