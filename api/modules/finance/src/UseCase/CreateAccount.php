<?php

declare(strict_types=1);

namespace Maggie\Finance\UseCase;

use Doctrine\ORM\EntityManagerInterface;
use Maggie\Finance\Entity\Account;

class CreateAccount
{
    public function __construct(
        private readonly EntityManagerInterface $em,
    ) {
    }

    public function execute(Account $account): Account
    {
        $this->em->persist($account);
        $this->em->flush();

        return $account;
    }
}
