<?php

declare(strict_types=1);

namespace Maggie\Finance\UseCase;

use Doctrine\ORM\EntityManagerInterface;
use Maggie\Finance\Entity\RecurringOperation;

class UpdateRecurringOperation
{
    public function __construct(
        private readonly EntityManagerInterface $em,
    ) {
    }

    public function execute(RecurringOperation $operation): RecurringOperation
    {
        $this->em->flush();

        return $operation;
    }
}
