<?php

declare(strict_types=1);

namespace Maggie\Finance\UseCase;

use Doctrine\ORM\EntityManagerInterface;
use Maggie\Core\Mercure\EntityBroadcaster;
use Maggie\Finance\Entity\Account;
use Maggie\Finance\Entity\Transaction;
use Maggie\Finance\Enum\TransactionStatus;
use Maggie\Finance\Import\MerchantExtractor;
use Maggie\Finance\Import\StatementRow;
use Maggie\Finance\Repository\TransactionRepository;

/**
 * Turns the rows of a bank export into transactions on an account.
 *
 * Re-importing the same file must be harmless: a bank reissues exports with
 * the same lines, and people re-export overlapping periods. What the pass
 * counts on is that a movement already stored stays stored once — while two
 * genuinely identical movements on the same day stay two.
 *
 * The pass also reports what it did line by line, because its first use is a
 * rehearsal someone reads before confirming: a count of skipped duplicates
 * does not say *which* movement was dropped, nor under which heading the rest
 * is about to be filed.
 */
class ImportStatement
{
    public function __construct(
        private readonly TransactionRepository $transactionRepository,
        private readonly CategorizeTransaction $categorizeTransaction,
        private readonly EntityManagerInterface $em,
        private readonly EntityBroadcaster $broadcaster,
    ) {
    }

    /**
     * @param list<StatementRow> $rows
     *
     * @return array{imported: int, skipped: int, categorized: int, counterpartiesCompleted: int, first: ?string, last: ?string, totalCents: int, rows: list<array{line: int, bookedAt: string, label: string, amountCents: int, currency: string, duplicate: bool, categoryName: ?string}>}
     */
    public function execute(Account $account, array $rows, bool $dryRun = false): array
    {
        $user = $account->getUser();

        // Same movement, several times in the file: only what exceeds what is
        // already stored gets written.
        $seenInFile = [];
        $written = [];
        $report = [];
        $imported = 0;
        $skipped = 0;
        $categorized = 0;
        $completed = 0;
        $totalCents = 0;
        $dates = [];

        foreach ($rows as $row) {
            $key = $row->fingerprint();
            $seenInFile[$key] = ($seenInFile[$key] ?? 0) + 1;

            $stored = $this->transactionRepository->findMatching(
                $account,
                $row->bookedAt,
                $row->amountCents,
                $row->label,
                $row->knownAs,
            );

            if ($seenInFile[$key] <= \count($stored)) {
                ++$skipped;
                $report[] = $this->describe($row, true, null);

                // A line stored before the counterparty had a field of its own
                // learns it when the bank re-sends it.
                $existing = $stored[$seenInFile[$key] - 1];
                if (!$dryRun && null === $existing->getCounterpartyKey() && null !== $row->counterpartyName) {
                    if (null !== $existing->setCounterpartyName($row->counterpartyName)->getCounterpartyKey()) {
                        $this->em->flush();
                        $this->broadcaster->broadcast($existing);
                        ++$completed;
                    }
                }
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
            $transaction->setCounterpartyName($row->counterpartyName ?? MerchantExtractor::extract($row->label));

            // The rules the user already wrote apply to the history too.
            if ($this->categorizeTransaction->apply($transaction)) {
                ++$categorized;
            }

            if (!$dryRun) {
                $this->em->persist($transaction);
                $written[] = $transaction;
            }

            ++$imported;
            $totalCents += $row->amountCents;
            $dates[] = $row->bookedAt;
            $report[] = $this->describe($row, false, $transaction->getCategory()?->getName());
        }

        if (!$dryRun && $imported > 0) {
            $this->em->flush();

            // Imports write straight to the database, so nothing on the bus
            // publishes or indexes them: without this the movements exist, the
            // screens already open never see them arrive, and the lists served
            // from Elasticsearch show none of them.
            foreach ($written as $transaction) {
                $this->broadcaster->broadcast($transaction);
            }
        }

        sort($dates);

        return [
            'imported' => $imported,
            'skipped' => $skipped,
            'categorized' => $categorized,
            'counterpartiesCompleted' => $completed,
            'first' => [] === $dates ? null : $dates[0]->format('Y-m-d'),
            'last' => [] === $dates ? null : end($dates)->format('Y-m-d'),
            'totalCents' => $totalCents,
            'rows' => $report,
        ];
    }

    /**
     * @return array{line: int, bookedAt: string, label: string, amountCents: int, currency: string, duplicate: bool, categoryName: ?string}
     */
    private function describe(StatementRow $row, bool $duplicate, ?string $categoryName): array
    {
        return [
            'line' => $row->lineNumber,
            'bookedAt' => $row->bookedAt->format('Y-m-d'),
            'label' => $row->label,
            'amountCents' => $row->amountCents,
            'currency' => $row->currency,
            'duplicate' => $duplicate,
            'categoryName' => $categoryName,
        ];
    }
}
