<?php

declare(strict_types=1);

namespace Maggie\Health\UseCase;

use Doctrine\ORM\EntityManagerInterface;
use Maggie\Health\Entity\Workout;

class DeleteWorkout
{
    public function __construct(
        private readonly EntityManagerInterface $em,
    ) {
    }

    public function execute(Workout $workout): void
    {
        $this->em->remove($workout);
        $this->em->flush();
    }
}
