<?php

declare(strict_types=1);

namespace Maggie\Finance\UseCase;

use Doctrine\ORM\EntityManagerInterface;
use Maggie\Finance\Entity\CategorizationRule;

class DeleteCategorizationRule
{
    public function __construct(
        private readonly EntityManagerInterface $em,
    ) {
    }

    public function execute(CategorizationRule $rule): void
    {
        $this->em->remove($rule);
        $this->em->flush();
    }
}
