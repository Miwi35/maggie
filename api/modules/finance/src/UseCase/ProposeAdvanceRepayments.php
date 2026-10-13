<?php

declare(strict_types=1);

namespace Maggie\Finance\UseCase;

use Maggie\Core\Entity\User;
use Maggie\Finance\Entity\Account;
use Maggie\Finance\Entity\Transaction;
use Maggie\Finance\Enum\AccountType;
use Maggie\Finance\Repository\AccountRepository;
use Maggie\Finance\Repository\TransactionRepository;

/**
 * What to transfer, from where and for how much, to give each open advance
 * back (shape decisions 14 and 15): one counter-transfer per advance, of
 * exactly what is left of it, so that the transfer the owner makes is the one
 * `ReconcileAdvanceRepayments` recognises.
 *
 * It proposes and never executes. The transfer leaves the checking account
 * the advance landed on, and is capped at what that account holds: a transfer
 * that would overdraw it is reduced to what is available, and the rest is
 * reported as not proposed rather than hidden. A reduced proposal is partial
 * and will not be reconciled — by design, since a partial amount cannot be
 * told from the month's regular top-up.
 */
class ProposeAdvanceRepayments
{
    public function __construct(
        private readonly GetSavingsAdvances $getSavingsAdvances,
        private readonly TransactionRepository $transactionRepository,
        private readonly AccountRepository $accountRepository,
    ) {
    }

    /**
     * @return array{
     *     proposals: list<array{fromAccountId: string, toAccountId: string, amountCents: int, advanceId: string, label: string, currency: string, isPartial: bool}>,
     *     totalCents: int,
     *     unproposed: list<array{advanceId: string, sourceAccountId: string, sourceAccountName: string, amountCents: int, reason: string}>,
     *     unproposedCents: int
     * }
     */
    public function execute(User $user): array
    {
        $open = array_values(array_filter(
            $this->getSavingsAdvances->execute($user)['advances'],
            static fn (array $advance): bool => !$advance['isClosed'],
        ));

        /** @var array<string, int> $available the balance still free on each checking account, by id */
        $available = [];
        $proposals = [];
        $unproposed = [];

        foreach ($open as $advance) {
            $advanceTransaction = $this->transactionRepository->find($advance['id']);
            $checking = null === $advanceTransaction ? null : $this->checkingFor($user, $advanceTransaction);

            if (null === $checking) {
                $unproposed[] = self::unproposed($advance, $advance['outstandingCents'], 'no_checking_account');
                continue;
            }

            $checkingId = (string) $checking->getId();
            $available[$checkingId] ??= max(0, $checking->getBalanceCents());

            $amount = min($advance['outstandingCents'], $available[$checkingId]);
            $available[$checkingId] -= $amount;

            if ($amount < $advance['outstandingCents']) {
                $unproposed[] = self::unproposed($advance, $advance['outstandingCents'] - $amount, 'insufficient_balance');
            }
            if (0 === $amount) {
                continue;
            }

            $proposals[] = [
                'fromAccountId' => $checkingId,
                'toAccountId' => $advance['sourceAccountId'],
                'amountCents' => $amount,
                'advanceId' => $advance['id'],
                'label' => sprintf('Remise avance %s', $advance['sourceAccountName']),
                'currency' => $checking->getCurrency(),
                'isPartial' => $amount < $advance['outstandingCents'],
            ];
        }

        return [
            'proposals' => $proposals,
            'totalCents' => array_sum(array_column($proposals, 'amountCents')),
            'unproposed' => $unproposed,
            'unproposedCents' => array_sum(array_column($unproposed, 'amountCents')),
        ];
    }

    /**
     * The checking account the advance landed on; failing that (an advance
     * marked by hand on a single leg), the user's checking account with the
     * most money in it.
     */
    private function checkingFor(User $user, Transaction $advance): ?Account
    {
        $landedOn = $advance->getCounterpart()?->getAccount();
        if (null !== $landedOn && AccountType::Checking === $landedOn->getType()) {
            return $landedOn;
        }

        $checking = array_filter(
            $this->accountRepository->findByUser($user),
            static fn (Account $account): bool => AccountType::Checking === $account->getType() && null === $account->getClosedAt(),
        );
        usort($checking, static fn (Account $a, Account $b): int => $b->getBalanceCents() <=> $a->getBalanceCents());

        return $checking[0] ?? null;
    }

    /**
     * @param array{id: string, sourceAccountId: string, sourceAccountName: string} $advance
     *
     * @return array{advanceId: string, sourceAccountId: string, sourceAccountName: string, amountCents: int, reason: string}
     */
    private static function unproposed(array $advance, int $amountCents, string $reason): array
    {
        return [
            'advanceId' => $advance['id'],
            'sourceAccountId' => $advance['sourceAccountId'],
            'sourceAccountName' => $advance['sourceAccountName'],
            'amountCents' => $amountCents,
            'reason' => $reason,
        ];
    }
}
