<?php

declare(strict_types=1);

namespace Maggie\Health\Doctrine;

use Doctrine\Bundle\DoctrineBundle\Attribute\AsEntityListener;
use Doctrine\ORM\Events;
use Maggie\Health\Entity\WorkoutExercise;
use Maggie\Health\Service\ExerciseCatalog;

/**
 * A session's exercise is read with the catalogue's name and unit (MAG-775),
 * which the row does not store: the catalogue is the reference. A slug the
 * catalogue no longer knows reads as itself, without a unit.
 */
#[AsEntityListener(event: Events::postLoad, entity: WorkoutExercise::class)]
final class DescribeWorkoutExerciseListener
{
    public function __construct(private readonly ExerciseCatalog $catalog)
    {
    }

    public function postLoad(WorkoutExercise $exercise): void
    {
        $entry = $this->catalog->exercise($exercise->getExercise());
        $exercise->describe((string) ($entry['name'] ?? $exercise->getExercise()), (string) ($entry['unit'] ?? ''));
    }
}
