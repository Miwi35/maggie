<?php

declare(strict_types=1);

namespace Maggie\Finance\UseCase;

use Doctrine\ORM\EntityManagerInterface;
use Maggie\Finance\Entity\Account;

class DeleteAccount
{
    public function __construct(
        private readonly EntityManagerInterface $em,
    ) {
    }

    public function execute(Account $account): void
    {
        $this->em->remove($account);
        $this->em->flush();
    }
}
