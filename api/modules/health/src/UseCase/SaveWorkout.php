<?php

declare(strict_types=1);

namespace Maggie\Health\UseCase;

use Doctrine\ORM\EntityManagerInterface;
use Maggie\Health\Entity\Workout;

class SaveWorkout
{
    public function __construct(
        private readonly EntityManagerInterface $em,
    ) {
    }

    public function execute(Workout $workout): Workout
    {
        $this->em->persist($workout);
        $this->em->flush();

        return $workout;
    }
}
