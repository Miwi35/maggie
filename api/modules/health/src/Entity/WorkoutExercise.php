<?php

declare(strict_types=1);

namespace Maggie\Health\Entity;

use Doctrine\Common\Collections\ArrayCollection;
use Doctrine\Common\Collections\Collection;
use Doctrine\ORM\Mapping as ORM;
use Maggie\Core\Contract\AggregateRoot;
use Maggie\Health\Dto\WorkoutExerciseInput;
use Maggie\Health\Enum\ExerciseFeeling;
use Symfony\Component\Uid\Ulid;

/**
 * One exercise of a session (MAG-775): what is prescribed — the catalogue's
 * exercise, its step on the ladder, sets × target, load, rest, tempo, RIR —
 * and, once done, the sets done and how it felt.
 *
 * The rows are recreated each time the session's list is written (it is sent
 * whole), so their ids mean nothing outside. `name` and `unit` come from the
 * catalogue: set on creation and on load ({@see \Maggie\Health\Doctrine\DescribeWorkoutExerciseListener}),
 * never stored.
 */
#[ORM\Entity]
#[ORM\Index(name: 'idx_workout_exercise_position', columns: ['workout_id', 'position'])]
#[ORM\Index(name: 'idx_workout_exercise_exercise', columns: ['exercise'])]
#[AggregateRoot('workout')]
class WorkoutExercise
{
    #[ORM\Id]
    #[ORM\Column(type: 'ulid')]
    private Ulid $id;

    #[ORM\ManyToOne(targetEntity: Workout::class, inversedBy: 'exerciseItems')]
    #[ORM\JoinColumn(nullable: false, onDelete: 'CASCADE')]
    private Workout $workout;

    /** 1 for the first exercise. */
    #[ORM\Column]
    private int $position;

    /** The catalogue's slug. */
    #[ORM\Column(length: 64)]
    private string $exercise;

    #[ORM\Column]
    private int $step;

    #[ORM\Column]
    private int $sets;

    #[ORM\Column]
    private int $target;

    #[ORM\Column]
    private int $targetMin;

    #[ORM\Column]
    private int $targetMax;

    #[ORM\Column(nullable: true)]
    private ?float $loadKg;

    #[ORM\Column]
    private int $restSeconds;

    #[ORM\Column(options: ['default' => false])]
    private bool $tempo;

    #[ORM\Column(nullable: true)]
    private ?int $targetRir;

    #[ORM\Column(length: 255, nullable: true)]
    private ?string $cue;

    #[ORM\Column(length: 16, nullable: true, enumType: ExerciseFeeling::class)]
    private ?ExerciseFeeling $feeling;

    /** @var Collection<int, WorkoutSet> */
    #[ORM\OneToMany(targetEntity: WorkoutSet::class, mappedBy: 'workoutExercise', cascade: ['persist', 'remove'], orphanRemoval: true)]
    #[ORM\OrderBy(['position' => 'ASC'])]
    private Collection $doneSets;

    private string $name = '';

    private string $unit = '';

    public function __construct(Workout $workout, int $position, WorkoutExerciseInput $input)
    {
        $this->id = new Ulid();
        $this->workout = $workout;
        $this->position = $position;
        $this->exercise = $input->exercise;
        $this->step = $input->step;
        $this->sets = $input->sets;
        $this->target = $input->target;
        $this->targetMin = $input->targetMin;
        $this->targetMax = $input->targetMax;
        $this->loadKg = $input->loadKg;
        $this->restSeconds = $input->restSeconds;
        $this->tempo = $input->tempo;
        $this->targetRir = $input->targetRir;
        $this->cue = $input->cue;
        $this->feeling = $input->feeling;
        $this->doneSets = new ArrayCollection();
        foreach ($input->done as $index => $set) {
            $this->doneSets->add(new WorkoutSet($this, $index + 1, $set['reps'], $set['seconds'], $set['meters'], $set['loadKg']));
        }
        $this->describe($input->name, $input->unit);
    }

    public function getId(): Ulid
    {
        return $this->id;
    }

    public function getWorkout(): Workout
    {
        return $this->workout;
    }

    public function getPosition(): int
    {
        return $this->position;
    }

    public function getExercise(): string
    {
        return $this->exercise;
    }

    /** The catalogue's name and unit, which the row does not store. */
    public function describe(string $name, string $unit): void
    {
        $this->name = $name;
        $this->unit = $unit;
    }

    /**
     * The shape the API, Mercure and the tool read (MAG-775): `done` lists the
     * sets done, each with its count in the exercise's unit.
     *
     * @return array<string, mixed>
     */
    public function toArray(): array
    {
        $done = $this->doneSets->getValues();
        usort($done, static fn (WorkoutSet $a, WorkoutSet $b): int => $a->getPosition() <=> $b->getPosition());

        return [
            'exercise' => $this->exercise,
            'name' => $this->name,
            'unit' => $this->unit,
            'step' => $this->step,
            'sets' => $this->sets,
            'target' => $this->target,
            'targetMin' => $this->targetMin,
            'targetMax' => $this->targetMax,
            'loadKg' => $this->loadKg,
            'restSeconds' => $this->restSeconds,
            'tempo' => $this->tempo,
            'targetRir' => $this->targetRir,
            'cue' => $this->cue,
            'feeling' => $this->feeling?->value,
            'done' => array_map(static fn (WorkoutSet $set): array => $set->toArray(), $done),
        ];
    }
}
