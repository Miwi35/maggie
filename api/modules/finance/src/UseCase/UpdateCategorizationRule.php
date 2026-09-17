<?php

declare(strict_types=1);

namespace Maggie\Finance\UseCase;

use Doctrine\ORM\EntityManagerInterface;
use Maggie\Finance\Entity\CategorizationRule;

class UpdateCategorizationRule
{
    public function __construct(
        private readonly EntityManagerInterface $em,
    ) {
    }

    public function execute(CategorizationRule $rule): CategorizationRule
    {
        $this->em->flush();

        return $rule;
    }
}
