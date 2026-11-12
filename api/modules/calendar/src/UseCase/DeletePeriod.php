<?php

declare(strict_types=1);

namespace Maggie\Calendar\UseCase;

use Doctrine\ORM\EntityManagerInterface;
use Maggie\Calendar\Entity\Period;

class DeletePeriod
{
    public function __construct(
        private readonly EntityManagerInterface $em,
    ) {
    }

    public function execute(Period $period): void
    {
        $this->em->remove($period);
        $this->em->flush();
    }
}
