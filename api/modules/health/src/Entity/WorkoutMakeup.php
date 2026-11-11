<?php

declare(strict_types=1);

namespace Maggie\Health\Entity;

use ApiPlatform\Metadata\ApiProperty;
use ApiPlatform\Metadata\ApiResource;
use ApiPlatform\Metadata\Get;
use Doctrine\DBAL\Types\Types;
use Doctrine\ORM\Mapping as ORM;
use Maggie\Core\Contract\MercurePublishable;
use Maggie\Core\Contract\OwnedByUserInterface;
use Maggie\Core\Entity\User;
use Maggie\Core\Mercure\Attribute\MercureCollection;
use Maggie\Core\Mercure\Trait\MercurePayloadFilterTrait;
use Maggie\Health\Enum\MakeupOption;
use Maggie\Health\Enum\WorkoutMakeupStatus;
use Maggie\Health\Repository\WorkoutMakeupRepository;
use Symfony\Component\Serializer\Attribute\Ignore;
use Symfony\Component\Uid\Ulid;

// A make-up proposed for a missed planned session (MAG-771): once per missed
// session, whatever becomes of its notification. The `workout_makeup`
// notification points here through its relatedEntityIri; answered by
// `POST /api/workout-makeups/{id}/choose` (ChooseWorkoutMakeupController).
// Not searched, so not indexed.
//
// Comments and not a docblock: API Platform publishes a class docblock as the
// resource description.
#[ORM\Entity(repositoryClass: WorkoutMakeupRepository::class)]
#[ORM\UniqueConstraint(name: 'uniq_workout_makeup_missed', columns: ['missed_id'])]
#[MercureCollection(self::COLLECTION)]
#[ApiResource(operations: [
    new Get(uriTemplate: '/workout-makeups/{id}'),
])]
class WorkoutMakeup implements MercurePublishable, OwnedByUserInterface
{
    use MercurePayloadFilterTrait;

    public const COLLECTION = '/api/workout-makeups';

    #[ORM\Id]
    #[ORM\Column(type: 'ulid')]
    #[ApiProperty(writable: false)]
    private Ulid $id;

    #[ORM\ManyToOne(targetEntity: User::class)]
    #[ORM\JoinColumn(nullable: false, onDelete: 'CASCADE')]
    #[ApiProperty(readable: false, writable: false)]
    private User $user;

    #[ORM\ManyToOne(targetEntity: Workout::class)]
    #[ORM\JoinColumn(nullable: false, onDelete: 'CASCADE')]
    #[ApiProperty(description: 'The planned session missed.')]
    private Workout $missed;

    #[ORM\Column(type: Types::DATETIMETZ_IMMUTABLE)]
    #[ApiProperty(description: 'When the missed session was to start.')]
    private \DateTimeImmutable $missedAt;

    #[ORM\Column(type: 'integer')]
    #[ApiProperty(description: 'The minutes of the « softer » make-up.')]
    private int $softerMinutes;

    #[ORM\Column(type: 'integer')]
    #[ApiProperty(description: 'The minutes of the « shorter » make-up.')]
    private int $shorterMinutes;

    #[ORM\Column(type: 'integer')]
    #[ApiProperty(description: 'The streak the make-up keeps.')]
    private int $streak;

    #[ORM\Column(length: 16, nullable: true, enumType: MakeupOption::class)]
    #[ApiProperty(description: 'The option chosen: softer, shorter or tomorrow; null until chosen.')]
    private ?MakeupOption $chosenOption = null;

    #[ORM\ManyToOne(targetEntity: Workout::class)]
    #[ORM\JoinColumn(nullable: true, onDelete: 'SET NULL')]
    #[ApiProperty(description: 'The planned make-up session the choice created.')]
    private ?Workout $chosenWorkout = null;

    #[ORM\Column(length: 16, enumType: WorkoutMakeupStatus::class)]
    #[ApiProperty(description: 'proposed, chosen, or obsolete (the missed session was kept the same day after all).')]
    private WorkoutMakeupStatus $status = WorkoutMakeupStatus::Proposed;

    public function __construct(User $user, Workout $missed, int $softerMinutes, int $shorterMinutes, int $streak)
    {
        $this->id = new Ulid();
        $this->user = $user;
        $this->missed = $missed;
        $this->missedAt = $missed->getStartedAt();
        $this->softerMinutes = $softerMinutes;
        $this->shorterMinutes = $shorterMinutes;
        $this->streak = $streak;
    }

    public function getId(): Ulid
    {
        return $this->id;
    }

    public function getUser(): User
    {
        return $this->user;
    }

    public function setUser(User $user): static
    {
        $this->user = $user;

        return $this;
    }

    public function getMissed(): Workout
    {
        return $this->missed;
    }

    public function getMissedAt(): \DateTimeImmutable
    {
        return $this->missedAt;
    }

    public function getSofterMinutes(): int
    {
        return $this->softerMinutes;
    }

    public function getShorterMinutes(): int
    {
        return $this->shorterMinutes;
    }

    public function getStreak(): int
    {
        return $this->streak;
    }

    public function getChosenOption(): ?MakeupOption
    {
        return $this->chosenOption;
    }

    public function getChosenWorkout(): ?Workout
    {
        return $this->chosenWorkout;
    }

    public function getStatus(): WorkoutMakeupStatus
    {
        return $this->status;
    }

    #[Ignore]
    public function isProposed(): bool
    {
        return WorkoutMakeupStatus::Proposed === $this->status;
    }

    public function choose(MakeupOption $option, Workout $makeup): void
    {
        $this->chosenOption = $option;
        $this->chosenWorkout = $makeup;
        $this->status = WorkoutMakeupStatus::Chosen;
    }

    public function markObsolete(): void
    {
        $this->status = WorkoutMakeupStatus::Obsolete;
    }

    /** The minutes of the session an option would plan. */
    public function minutesFor(MakeupOption $option): int
    {
        return match ($option) {
            MakeupOption::Softer => $this->softerMinutes,
            MakeupOption::Shorter => $this->shorterMinutes,
            MakeupOption::Tomorrow => $this->missed->getDurationMinutes(),
        };
    }

    public static function iri(WorkoutMakeup $makeup): string
    {
        return self::COLLECTION.'/'.$makeup->getId();
    }

    /** @return array<string, mixed> */
    public function toMercurePayload(?array $changedProperties = null): array
    {
        return self::filterPayload([
            'id' => (string) $this->id,
            'missed' => Workout::iri($this->missed),
            'missedAt' => $this->missedAt->format(\DATE_ATOM),
            'softerMinutes' => $this->softerMinutes,
            'shorterMinutes' => $this->shorterMinutes,
            'streak' => $this->streak,
            'chosenOption' => $this->chosenOption?->value,
            'chosenWorkout' => null === $this->chosenWorkout ? null : Workout::iri($this->chosenWorkout),
            'status' => $this->status->value,
        ], $changedProperties);
    }
}
