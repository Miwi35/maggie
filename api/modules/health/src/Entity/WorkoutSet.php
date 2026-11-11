<?php

declare(strict_types=1);

namespace Maggie\Health\Entity;

use Doctrine\ORM\Mapping as ORM;
use Symfony\Component\Uid\Ulid;

/**
 * One set done of an exercise of a session (MAG-775): the count in the
 * exercise's unit — repetitions, seconds or metres, exactly one of them — and
 * the load, for an exercise that takes one. Read through
 * {@see WorkoutExercise::toArray()}, never on its own.
 */
#[ORM\Entity]
#[ORM\Index(name: 'idx_workout_set_position', columns: ['workout_exercise_id', 'position'])]
class WorkoutSet
{
    #[ORM\Id]
    #[ORM\Column(type: 'ulid')]
    private Ulid $id;

    #[ORM\ManyToOne(targetEntity: WorkoutExercise::class, inversedBy: 'doneSets')]
    #[ORM\JoinColumn(nullable: false, onDelete: 'CASCADE')]
    private WorkoutExercise $workoutExercise;

    /** 1 for the first set. */
    #[ORM\Column]
    private int $position;

    #[ORM\Column(nullable: true)]
    private ?int $reps = null;

    #[ORM\Column(nullable: true)]
    private ?int $seconds = null;

    #[ORM\Column(nullable: true)]
    private ?int $meters = null;

    #[ORM\Column(nullable: true)]
    private ?float $loadKg = null;

    public function __construct(WorkoutExercise $workoutExercise, int $position, ?int $reps, ?int $seconds, ?int $meters, ?float $loadKg)
    {
        $this->id = new Ulid();
        $this->workoutExercise = $workoutExercise;
        $this->position = $position;
        $this->reps = $reps;
        $this->seconds = $seconds;
        $this->meters = $meters;
        $this->loadKg = $loadKg;
    }

    public function getId(): Ulid
    {
        return $this->id;
    }

    public function getPosition(): int
    {
        return $this->position;
    }

    /** @return array{reps?: int, seconds?: int, meters?: int, loadKg?: float} only what the set has */
    public function toArray(): array
    {
        return array_filter([
            'reps' => $this->reps,
            'seconds' => $this->seconds,
            'meters' => $this->meters,
            'loadKg' => $this->loadKg,
        ], static fn (int|float|null $value): bool => null !== $value);
    }
}
