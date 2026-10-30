<?php

declare(strict_types=1);

namespace Maggie\Health\UseCase;

use Doctrine\ORM\EntityManagerInterface;
use Maggie\Health\Entity\BodyMeasurement;

class DeleteBodyMeasurement
{
    public function __construct(
        private readonly EntityManagerInterface $em,
    ) {
    }

    public function execute(BodyMeasurement $measurement): void
    {
        $this->em->remove($measurement);
        $this->em->flush();
    }
}
