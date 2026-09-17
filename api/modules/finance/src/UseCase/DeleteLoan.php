<?php

declare(strict_types=1);

namespace Maggie\Finance\UseCase;

use Doctrine\ORM\EntityManagerInterface;
use Maggie\Finance\Entity\Loan;

class DeleteLoan
{
    public function __construct(
        private readonly EntityManagerInterface $em,
    ) {
    }

    public function execute(Loan $loan): void
    {
        $this->em->remove($loan);
        $this->em->flush();
    }
}
