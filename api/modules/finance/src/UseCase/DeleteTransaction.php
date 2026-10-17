<?php

declare(strict_types=1);

namespace Maggie\Finance\UseCase;

use Doctrine\ORM\EntityManagerInterface;
use Maggie\Finance\Entity\Transaction;
use Maggie\Finance\Repository\PeerDebtRepository;

class DeleteTransaction
{
    public function __construct(
        private readonly EntityManagerInterface $em,
        private readonly PeerDebtRepository $peerDebtRepository,
    ) {
    }

    public function execute(Transaction $transaction): void
    {
        // The créance of a shared spend goes with it, unless someone already
        // gave money back on it: then it stays, without its origin.
        $debt = $this->peerDebtRepository->findOneByOriginTransaction($transaction);
        if (null !== $debt) {
            if (0 === $debt->getRepaidCents()) {
                $this->em->remove($debt);
            } else {
                $debt->setOriginTransaction(null);
            }
        }

        $this->em->remove($transaction);
        $this->em->flush();
    }
}
