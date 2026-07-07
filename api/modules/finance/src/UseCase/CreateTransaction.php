<?php

declare(strict_types=1);

namespace Maggie\Finance\UseCase;

use Doctrine\ORM\EntityManagerInterface;
use Maggie\Finance\Entity\Transaction;

class CreateTransaction
{
    public function __construct(
        private readonly EntityManagerInterface $em,
    ) {
    }

    public function execute(Transaction $transaction): Transaction
    {
        $this->em->persist($transaction);
        $this->em->flush();

        return $transaction;
    }
}
