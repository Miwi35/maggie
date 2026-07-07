<?php

declare(strict_types=1);

namespace Maggie\Finance\UseCase;

use Doctrine\ORM\EntityManagerInterface;
use Maggie\Finance\Entity\Transaction;

class DeleteTransaction
{
    public function __construct(
        private readonly EntityManagerInterface $em,
    ) {
    }

    public function execute(Transaction $transaction): void
    {
        $this->em->remove($transaction);
        $this->em->flush();
    }
}
