<?php

declare(strict_types=1);

namespace Maggie\Finance\UseCase;

use Doctrine\ORM\EntityManagerInterface;
use Maggie\Finance\Entity\RecurringOperation;

class DeleteRecurringOperation
{
    public function __construct(
        private readonly EntityManagerInterface $em,
    ) {
    }

    public function execute(RecurringOperation $operation): void
    {
        $this->em->remove($operation);
        $this->em->flush();
    }
}
