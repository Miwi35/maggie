<?php

declare(strict_types=1);

namespace Maggie\Health\UseCase;

use Doctrine\ORM\EntityManagerInterface;
use Maggie\Health\Entity\BodyMeasurement;

class SaveBodyMeasurement
{
    public function __construct(
        private readonly EntityManagerInterface $em,
    ) {
    }

    public function execute(BodyMeasurement $measurement): BodyMeasurement
    {
        $this->em->persist($measurement);
        $this->em->flush();

        return $measurement;
    }
}
