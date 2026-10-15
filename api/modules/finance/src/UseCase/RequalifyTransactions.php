<?php

declare(strict_types=1);

namespace Maggie\Finance\UseCase;

use Doctrine\ORM\EntityManagerInterface;
use Maggie\Finance\Entity\QualifyingOperation;
use Maggie\Finance\Repository\TransactionRepository;

/**
 * Copies an operation's qualification again onto the transactions it
 * qualified, after the operation changed (spec « Opération et transaction »).
 * A line the owner qualified by hand is no longer the operation's, and is left
 * alone.
 */
class RequalifyTransactions
{
    public function __construct(
        private readonly TransactionRepository $transactionRepository,
        private readonly EntityManagerInterface $em,
    ) {
    }

    /** @return int how many lines took the new qualification */
    public function execute(QualifyingOperation $operation): int
    {
        $qualification = $operation->getQualification();
        $requalified = 0;

        foreach ($this->transactionRepository->findQualifiedBy($operation) as $transaction) {
            if ($transaction->getQualification()->equals($qualification)) {
                continue;
            }

            $transaction->qualify($qualification, $operation);
            ++$requalified;
        }

        if ($requalified > 0) {
            $this->em->flush();
        }

        return $requalified;
    }
}
