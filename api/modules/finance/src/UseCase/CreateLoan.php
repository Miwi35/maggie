<?php

declare(strict_types=1);

namespace Maggie\Finance\UseCase;

use Doctrine\ORM\EntityManagerInterface;
use Maggie\Finance\Entity\Loan;

class CreateLoan
{
    public function __construct(
        private readonly EntityManagerInterface $em,
    ) {
    }

    public function execute(Loan $loan): Loan
    {
        $this->em->persist($loan);
        $this->em->flush();

        return $loan;
    }
}
