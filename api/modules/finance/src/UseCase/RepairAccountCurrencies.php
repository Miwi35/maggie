<?php

declare(strict_types=1);

namespace Maggie\Finance\UseCase;

use Doctrine\ORM\EntityManagerInterface;
use Maggie\Core\Mercure\EntityBroadcaster;
use Maggie\Finance\Repository\AccountRepository;
use Maggie\Finance\Repository\TransactionRepository;
use Maggie\Finance\Specification\RealCurrency;

/**
 * Gives a real currency back to the accounts a bank once left with « XXX »
 * (MAG-376): the one their movements are mostly in, else the euro.
 *
 * Run it once to check — a dry run lists the accounts, none means nothing to
 * repair — and again for real if any is listed. An account whose currency is
 * real is never touched.
 */
class RepairAccountCurrencies
{
    public function __construct(
        private readonly AccountRepository $accountRepository,
        private readonly TransactionRepository $transactionRepository,
        private readonly EntityManagerInterface $em,
        private readonly EntityBroadcaster $broadcaster,
    ) {
    }

    /** @return list<array{id: string, name: string, from: string, to: string}> */
    public function execute(bool $dryRun = false): array
    {
        $repaired = [];
        $touched = [];

        foreach ($this->accountRepository->findAll() as $account) {
            if (RealCurrency::isReal($account->getCurrency())) {
                continue;
            }

            $to = RealCurrency::resolve(...$this->transactionRepository->findCurrenciesOfAccount($account));
            $repaired[] = [
                'id' => (string) $account->getId(),
                'name' => $account->getName(),
                'from' => $account->getCurrency(),
                'to' => $to,
            ];

            if (!$dryRun) {
                $account->setCurrency($to);
                $touched[] = $account;
            }
        }

        if ([] !== $touched) {
            $this->em->flush();

            foreach ($touched as $account) {
                $this->broadcaster->broadcast($account);
            }
        }

        return $repaired;
    }
}
