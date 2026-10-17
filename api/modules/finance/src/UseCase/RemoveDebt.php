<?php

declare(strict_types=1);

namespace Maggie\Finance\UseCase;

use Doctrine\ORM\EntityManagerInterface;
use Maggie\Finance\Entity\Transaction;
use Maggie\Finance\Enum\PeerDebtDirection;
use Maggie\Finance\Repository\PeerDebtRepository;

/**
 * The received transfer is a plain income again: all of it counts, its dette
 * is forgotten — refused once part of it was given back.
 */
class RemoveDebt
{
    public function __construct(
        private readonly EntityManagerInterface $em,
        private readonly PeerDebtRepository $peerDebtRepository,
    ) {
    }

    /** @throws \DomainException when a repayment is already allocated to the dette */
    public function execute(Transaction $transaction): Transaction
    {
        $debt = $this->peerDebtRepository->findOneByOriginTransaction($transaction);
        if (null !== $debt && PeerDebtDirection::IOwe === $debt->getDirection()) {
            if ($debt->getRepaidCents() > 0) {
                throw new \DomainException('Part of this dette was already given back: it cannot be removed.');
            }
            $this->em->remove($debt);
            $this->em->flush();
        }

        return $transaction;
    }
}
