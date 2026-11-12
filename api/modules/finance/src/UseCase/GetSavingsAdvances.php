<?php

declare(strict_types=1);

namespace Maggie\Finance\UseCase;

use Maggie\Core\Entity\User;
use Maggie\Finance\Entity\Account;
use Maggie\Finance\Entity\Transaction;
use Maggie\Finance\Enum\TransferKind;
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
 * A source no bank sync gives a debit leg — a livret typed by hand — is read
 * from the single-legged lines tied to it with `advanceSource` (MAG-806): what
 * is left is what was advanced minus what was given back, a global delta and
 * no pairing (owner, 12 Nov.). Its credits are listed in `advances[]`, what
 * was given back covering the oldest first, so that each one says what is
 * left of it and the source adds up to the delta.
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
     *     bySource: list<array{sourceAccountId: string, sourceAccountName: string, advancedCents: int, repaidCents: int, outstandingCents: int, openCount: int}>,
     *     totalOutstandingCents: int
     * }
     */
    public function execute(User $user): array
    {
        $debits = $this->transactionRepository->findAdvanceDebits($user);
        $repaid = $this->transactionRepository->sumRepaidByAdvance($user, $debits);

        $advances = [];
        foreach ($debits as $debit) {
            $amount = -$debit->getAmountCents();
            $advances[] = self::advance($debit, $debit->getAccount(), $amount, $repaid[(string) $debit->getId()] ?? 0);
        }

        foreach ($this->sourcedAdvances($user) as $advance) {
            $advances[] = $advance;
        }
        // Stable: on one day, the legged advances keep coming first.
        usort($advances, static fn (array $a, array $b) => $a['openedOn'] <=> $b['openedOn']);

        // What was advanced and given back counts the whole history of a
        // source; only a source with something left to give back is listed.
        $bySource = [];
        foreach ($advances as $advance) {
            $accountId = $advance['sourceAccountId'];
            $bySource[$accountId] ??= [
                'sourceAccountId' => $accountId,
                'sourceAccountName' => $advance['sourceAccountName'],
                'advancedCents' => 0,
                'repaidCents' => 0,
                'outstandingCents' => 0,
                'openCount' => 0,
            ];
            $bySource[$accountId]['advancedCents'] += $advance['amountCents'];
            $bySource[$accountId]['repaidCents'] += $advance['amountCents'] - $advance['outstandingCents'];
            $bySource[$accountId]['outstandingCents'] += $advance['outstandingCents'];
            $bySource[$accountId]['openCount'] += $advance['isClosed'] ? 0 : 1;
        }

        $bySource = array_values(array_filter($bySource, static fn (array $source) => $source['outstandingCents'] > 0));
        usort($bySource, static fn (array $a, array $b) => [$b['outstandingCents'], $a['sourceAccountName']]
            <=> [$a['outstandingCents'], $b['sourceAccountName']]);

        return [
            'advances' => $advances,
            'bySource' => $bySource,
            'totalOutstandingCents' => array_sum(array_column($bySource, 'outstandingCents')),
        ];
    }

    /**
     * The credits of each source tied by hand, with what was given back to
     * that source spread over them, oldest first.
     *
     * @return list<array{id: string, sourceAccountId: string, sourceAccountName: string, amountCents: int, openedOn: string, repaidCents: int, outstandingCents: int, isClosed: bool}>
     */
    private function sourcedAdvances(User $user): array
    {
        /** @var array<string, array{source: Account, credits: list<Transaction>, repaid: int}> $sources */
        $sources = [];
        foreach ($this->transactionRepository->findSourcedAdvanceLines($user) as $line) {
            $source = $line->getAdvanceSource();
            if (null === $source) {
                continue;
            }

            $sourceId = (string) $source->getId();
            $sources[$sourceId] ??= ['source' => $source, 'credits' => [], 'repaid' => 0];
            if (TransferKind::Advance === $line->getTransferKind()) {
                $sources[$sourceId]['credits'][] = $line;
            } else {
                $sources[$sourceId]['repaid'] += abs($line->getAmountCents());
            }
        }

        $advances = [];
        foreach ($sources as ['source' => $source, 'credits' => $credits, 'repaid' => $left]) {
            foreach ($credits as $credit) {
                $amount = abs($credit->getAmountCents());
                $covered = min($amount, $left);
                $left -= $covered;
                $advances[] = self::advance($credit, $source, $amount, $covered);
            }
        }

        return $advances;
    }

    /** @return array{id: string, sourceAccountId: string, sourceAccountName: string, amountCents: int, openedOn: string, repaidCents: int, outstandingCents: int, isClosed: bool} */
    private static function advance(Transaction $line, Account $source, int $amount, int $repaidCents): array
    {
        $outstanding = max(0, $amount - $repaidCents);

        return [
            'id' => (string) $line->getId(),
            'sourceAccountId' => (string) $source->getId(),
            'sourceAccountName' => $source->getName(),
            'amountCents' => $amount,
            'openedOn' => $line->getBookedAt()->format('Y-m-d'),
            'repaidCents' => $repaidCents,
            'outstandingCents' => $outstanding,
            'isClosed' => 0 === $outstanding,
        ];
    }
}
