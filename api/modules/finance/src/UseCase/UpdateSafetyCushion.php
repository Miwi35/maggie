<?php

declare(strict_types=1);

namespace Maggie\Finance\UseCase;

use Doctrine\ORM\EntityManagerInterface;
use Maggie\Finance\Entity\SafetyCushion;

class UpdateSafetyCushion
{
    public function __construct(
        private readonly EntityManagerInterface $em,
    ) {
    }

    public function execute(SafetyCushion $cushion): SafetyCushion
    {
        $this->em->flush();

        return $cushion;
    }
}
