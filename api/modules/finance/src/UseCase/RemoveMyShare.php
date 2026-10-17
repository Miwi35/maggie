<?php

declare(strict_types=1);

namespace Maggie\Finance\UseCase;

use Doctrine\ORM\EntityManagerInterface;
use Maggie\Finance\Entity\Transaction;
use Maggie\Finance\Repository\PeerDebtRepository;

/**
 * The spend is the owner's alone again: its whole amount goes back into the
 * envelopes and its créance is forgotten — refused once someone has given
 * money back on it, which would then have nothing to repay.
 */
class RemoveMyShare
{
    public function __construct(
        private readonly EntityManagerInterface $em,
        private readonly PeerDebtRepository $peerDebtRepository,
    ) {
    }

    /** @throws \DomainException when a repayment is already allocated to the créance */
    public function execute(Transaction $transaction): Transaction
    {
        $debt = $this->peerDebtRepository->findOneByOriginTransaction($transaction);
        if (null !== $debt) {
            if ($debt->getRepaidCents() > 0) {
                throw new \DomainException('Someone already gave money back on this créance: the share cannot be removed.');
            }
            $this->em->remove($debt);
        }

        $transaction->setMyShareCents(null);
        $this->em->flush();

        return $transaction;
    }
}
