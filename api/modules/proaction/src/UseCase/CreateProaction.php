<?php

declare(strict_types=1);

namespace Maggie\Proaction\UseCase;

use Maggie\Proaction\Entity\Proaction;
use Doctrine\ORM\EntityManagerInterface;

class CreateProaction
{
    public function __construct(
        private readonly EntityManagerInterface $em,
    ) {
    }

    public function execute(Proaction $proaction): Proaction
    {
        $this->em->persist($proaction);
        $this->em->flush();

        return $proaction;
    }
}
