<?php

declare(strict_types=1);

namespace Maggie\Finance\UseCase;

use Doctrine\ORM\EntityManagerInterface;
use Maggie\Finance\Entity\Transaction;
use Maggie\Finance\Repository\PeerDebtAllocationRepository;
use Maggie\Finance\Repository\PeerDebtRepository;

class DeleteTransaction
{
    public function __construct(
        private readonly EntityManagerInterface $em,
        private readonly PeerDebtRepository $peerDebtRepository,
        private readonly PeerDebtAllocationRepository $allocationRepository,
    ) {
    }

    public function execute(Transaction $transaction): void
    {
        // What it gave back is owed again (MAG-453).
        foreach ($this->allocationRepository->findByTransaction($transaction) as $allocation) {
            $share = $allocation->getShare();
            $share->setRepaidCents(max(0, $share->getRepaidCents() - $allocation->getAmountCents()));
            $this->em->remove($allocation);
        }

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
