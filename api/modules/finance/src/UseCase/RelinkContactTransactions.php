<?php

declare(strict_types=1);

namespace Maggie\Finance\UseCase;

use Doctrine\ORM\EntityManagerInterface;
use Maggie\Core\Entity\User;
use Maggie\Finance\Contact\CounterpartyMatch;
use Maggie\Finance\Repository\ContactCounterpartyRepository;
use Maggie\Finance\Repository\TransactionRepository;

/**
 * Puts every transaction of a user back with the contact its counterparties
 * say, once one was linked or removed: the history follows, not only the
 * lines imported afterwards. Only the lines whose contact changes are written.
 */
class RelinkContactTransactions
{
    public function __construct(
        private readonly EntityManagerInterface $em,
        private readonly TransactionRepository $transactionRepository,
        private readonly ContactCounterpartyRepository $counterpartyRepository,
    ) {
    }

    /** @return int how many lines changed contact */
    public function execute(User $user): int
    {
        $counterparties = $this->counterpartyRepository->findByUser($user);

        $changed = 0;
        foreach ($this->transactionRepository->findContactCandidates($user) as $transaction) {
            $contact = CounterpartyMatch::contactFor($transaction, $counterparties);
            if ($contact !== $transaction->getContact()) {
                $transaction->setContact($contact);
                ++$changed;
            }
        }

        if ($changed > 0) {
            $this->em->flush();
        }

        return $changed;
    }
}
