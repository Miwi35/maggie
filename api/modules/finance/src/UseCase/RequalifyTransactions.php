<?php

declare(strict_types=1);

namespace Maggie\Finance\UseCase;

use Doctrine\ORM\EntityManagerInterface;
use Maggie\Finance\Entity\QualifyingOperation;
use Maggie\Finance\Repository\TransactionRepository;
use Maggie\Finance\Service\TransactionNatureGuard;

/**
 * Copies an operation's qualification again onto the transactions it
 * qualified, after the operation changed (spec « Opération et transaction »).
 * A line the owner qualified by hand is no longer the operation's, and is left
 * alone; so is a line the new category contradicts — the series turned from
 * an expense into an income — rather than be refused.
 */
class RequalifyTransactions
{
    public function __construct(
        private readonly TransactionRepository $transactionRepository,
        private readonly TransactionNatureGuard $natureGuard,
        private readonly EntityManagerInterface $em,
    ) {
    }

    /** @return int how many lines took the new qualification */
    public function execute(QualifyingOperation $operation): int
    {
        $qualification = $operation->getQualification();
        $requalified = 0;

        foreach ($this->transactionRepository->findQualifiedBy($operation) as $transaction) {
            $category = $qualification->getCategory();
            if ($transaction->getQualification()->equals($qualification)
                || (null !== $category && !$this->natureGuard->isCompatible($transaction->getAmountCents(), $category))) {
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
