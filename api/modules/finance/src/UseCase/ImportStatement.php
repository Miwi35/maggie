<?php

declare(strict_types=1);

namespace Maggie\Finance\UseCase;

use Doctrine\ORM\EntityManagerInterface;
use Maggie\Finance\Entity\Account;
use Maggie\Finance\Entity\Transaction;
use Maggie\Finance\Enum\TransactionStatus;
use Maggie\Finance\Import\StatementRow;
use Maggie\Finance\Repository\TransactionRepository;

/**
 * Turns the rows of a bank export into transactions on an account.
 *
 * Re-importing the same file must be harmless: a bank reissues exports with
 * the same lines, and people re-export overlapping periods. What the pass
 * counts on is that a movement already stored stays stored once — while two
 * genuinely identical movements on the same day stay two.
 */
class ImportStatement
{
    public function __construct(
        private readonly TransactionRepository $transactionRepository,
        private readonly CategorizeTransaction $categorizeTransaction,
        private readonly EntityManagerInterface $em,
    ) {
    }

    /**
     * @param list<StatementRow> $rows
     *
     * @return array{imported: int, skipped: int, categorized: int, first: ?string, last: ?string, totalCents: int}
     */
    public function execute(Account $account, array $rows, bool $dryRun = false): array
    {
        $user = $account->getUser();

        // Same movement, several times in the file: only what exceeds what is
        // already stored gets written.
        $seenInFile = [];
        $imported = 0;
        $skipped = 0;
        $categorized = 0;
        $totalCents = 0;
        $dates = [];

        foreach ($rows as $row) {
            $key = $row->fingerprint();
            $seenInFile[$key] = ($seenInFile[$key] ?? 0) + 1;

            $alreadyStored = $this->transactionRepository->countMatching(
                $account,
                $row->bookedAt,
                $row->amountCents,
                $row->label,
            );

            if ($seenInFile[$key] <= $alreadyStored) {
                ++$skipped;
                continue;
            }

            $transaction = new Transaction();
            $transaction->setUser($user);
            $transaction->setAccount($account);
            $transaction->setLabel($row->label);
            $transaction->setAmountCents($row->amountCents);
            $transaction->setCurrency($row->currency);
            $transaction->setBookedAt($row->bookedAt);
            $transaction->setStatus(TransactionStatus::Spent);

            // The rules the user already wrote apply to the history too.
            if ($this->categorizeTransaction->apply($transaction)) {
                ++$categorized;
            }

            if (!$dryRun) {
                $this->em->persist($transaction);
            }

            ++$imported;
            $totalCents += $row->amountCents;
            $dates[] = $row->bookedAt;
        }

        if (!$dryRun && $imported > 0) {
            $this->em->flush();
        }

        sort($dates);

        return [
            'imported' => $imported,
            'skipped' => $skipped,
            'categorized' => $categorized,
            'first' => $dates === [] ? null : $dates[0]->format('Y-m-d'),
            'last' => $dates === [] ? null : end($dates)->format('Y-m-d'),
            'totalCents' => $totalCents,
        ];
    }
}
