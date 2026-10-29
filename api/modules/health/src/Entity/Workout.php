<?php

declare(strict_types=1);

namespace Maggie\Health\Entity;

use ApiPlatform\Doctrine\Orm\Filter\DateFilter;
use ApiPlatform\Doctrine\Orm\Filter\SearchFilter;
use ApiPlatform\Metadata\ApiFilter;
use ApiPlatform\Metadata\ApiProperty;
use ApiPlatform\Metadata\ApiResource;
use ApiPlatform\Metadata\Delete;
use ApiPlatform\Metadata\Get;
use ApiPlatform\Metadata\GetCollection;
use ApiPlatform\Metadata\Patch;
use ApiPlatform\Metadata\Post;
use Doctrine\DBAL\Types\Types;
use Doctrine\ORM\Mapping as ORM;
use Maggie\Core\Contract\MercurePublishable;
use Maggie\Core\Contract\OwnedByUserInterface;
use Maggie\Core\Entity\User;
use Maggie\Core\Mercure\Trait\MercurePayloadFilterTrait;
use Maggie\Health\Enum\WorkoutFeeling;
use Maggie\Health\Enum\WorkoutSource;
use Maggie\Health\Enum\WorkoutType;
use Maggie\Health\Repository\WorkoutRepository;
use Maggie\Health\State\CreateWorkoutProcessor;
use Maggie\Health\State\DeleteWorkoutProcessor;
use Maggie\Health\State\UpdateWorkoutProcessor;
use Symfony\Bridge\Doctrine\Validator\Constraints\UniqueEntity;
use Symfony\Component\Serializer\Attribute\Context;
use Symfony\Component\Serializer\Normalizer\DateTimeNormalizer;
use Symfony\Component\Uid\Ulid;
use Symfony\Component\Validator\Constraints as Assert;
use Symfony\Component\Validator\Context\ExecutionContextInterface;

// A training session, done or planned (MAG-705): a type, a title, a start, a
// duration, and what the watch or the user knew about it. Served by Doctrine:
// the list is filtered by date and user, nothing is searched, so it is not
// indexed (like Trip). `externalId` de-duplicates what comes from outside
// (a watch, the Sport app): unique per user, null allowed many times.
//
// Comments and not a docblock: API Platform publishes a class docblock as the
// resource description.
#[ORM\Entity(repositoryClass: WorkoutRepository::class)]
#[ORM\Index(name: 'idx_workout_user_started_at', columns: ['user_id', 'started_at'])]
#[ORM\UniqueConstraint(name: 'uniq_workout_user_external', columns: ['user_id', 'external_id'])]
#[UniqueEntity(fields: ['user', 'externalId'], message: 'This externalId is already used by another workout.', ignoreNull: 'externalId')]
#[ApiFilter(SearchFilter::class, properties: ['type' => 'exact', 'source' => 'exact'])]
#[ApiFilter(DateFilter::class, properties: ['startedAt'])]
#[ApiResource(operations: [
    new GetCollection(uriTemplate: '/workouts', order: ['startedAt' => 'DESC']),
    new Get(uriTemplate: '/workouts/{id}'),
    new Post(uriTemplate: '/workouts', processor: CreateWorkoutProcessor::class),
    new Patch(uriTemplate: '/workouts/{id}', processor: UpdateWorkoutProcessor::class),
    new Delete(uriTemplate: '/workouts/{id}', processor: DeleteWorkoutProcessor::class),
])]
class Workout implements MercurePublishable, OwnedByUserInterface
{
    use MercurePayloadFilterTrait;

    /**
     * A time without offset is read in this zone, which no offset string can
     * produce: {@see self::validateOffset()} then refuses it instead of taking
     * the server's zone for the user's.
     */
    private const NO_OFFSET_ZONE = 'Pacific/Kiritimati';

    public const MAX_DURATION_MINUTES = 1440;

    public const MAX_DISTANCE_METERS = 1_000_000;

    public const MAX_KCAL = 20_000;

    public const MIN_HEART_RATE = 20;

    public const MAX_HEART_RATE = 250;

    // The admin sends the record back with its IRI as `id`: never denormalized.
    #[ORM\Id]
    #[ORM\Column(type: 'ulid')]
    #[ApiProperty(writable: false)]
    private Ulid $id;

    #[ORM\Column(length: 16, enumType: WorkoutType::class)]
    #[ApiProperty(description: 'run, walk, bike, swim, strength, climbing or other.')]
    private WorkoutType $type;

    #[ORM\Column(length: 255)]
    #[Assert\NotBlank(normalizer: 'trim')]
    #[Assert\Length(max: 255)]
    private string $title;

    #[ORM\Column(type: Types::DATETIMETZ_IMMUTABLE)]
    #[Assert\NotNull]
    #[Context(denormalizationContext: [DateTimeNormalizer::TIMEZONE_KEY => self::NO_OFFSET_ZONE])]
    #[ApiProperty(description: 'When the workout started (or starts, if planned): ISO 8601 with offset, e.g. 2026-10-20T07:10:00+02:00.')]
    private \DateTimeImmutable $startedAt;

    #[ORM\Column(type: 'integer')]
    #[Assert\Range(min: 1, max: self::MAX_DURATION_MINUTES)]
    #[ApiProperty(description: 'Duration in whole minutes (1 to 1440).')]
    private int $durationMinutes;

    #[ORM\Column(type: 'integer', nullable: true)]
    #[Assert\Range(min: 0, max: self::MAX_DISTANCE_METERS)]
    #[ApiProperty(description: 'Distance in whole metres (0 to 1 000 000): 5.4 km is 5400.')]
    private ?int $distanceMeters = null;

    #[ORM\Column(type: 'integer', nullable: true)]
    #[Assert\Range(min: 0, max: self::MAX_KCAL)]
    #[ApiProperty(description: 'Energy spent, in whole kilocalories (0 to 20 000).')]
    private ?int $kcal = null;

    #[ORM\Column(type: 'integer', nullable: true)]
    #[Assert\Range(min: self::MIN_HEART_RATE, max: self::MAX_HEART_RATE)]
    #[ApiProperty(description: 'Average heart rate, in beats per minute (20 to 250).')]
    private ?int $avgHeartRate = null;

    #[ORM\Column(length: 16, nullable: true, enumType: WorkoutFeeling::class)]
    #[ApiProperty(description: 'How it felt: very_good, good, hard or very_hard.')]
    private ?WorkoutFeeling $feeling = null;

    #[ORM\Column(type: Types::TEXT, nullable: true)]
    #[Assert\Length(max: 2000)]
    private ?string $notes = null;

    #[ORM\Column(length: 16, enumType: WorkoutSource::class)]
    #[ApiProperty(description: 'manual, watch, activity, estimated or planned (still to do).')]
    private WorkoutSource $source = WorkoutSource::Manual;

    #[ORM\Column(length: 255, nullable: true)]
    #[Assert\Length(max: 255)]
    #[ApiProperty(description: 'The identifier of the workout in the system it comes from; unique per user, it de-duplicates an import.')]
    private ?string $externalId = null;

    #[ORM\ManyToOne(targetEntity: User::class)]
    #[ORM\JoinColumn(nullable: false, onDelete: 'CASCADE')]
    private User $user;

    public function __construct()
    {
        $this->id = new Ulid();
    }

    #[Assert\Callback]
    public function validateOffset(ExecutionContextInterface $context): void
    {
        if (isset($this->startedAt) && self::NO_OFFSET_ZONE === $this->startedAt->getTimezone()->getName()) {
            $context->buildViolation('startedAt must have a time and an offset, e.g. 2026-10-20T07:10:00+02:00.')->atPath('startedAt')->addViolation();
        }
    }

    public function getId(): Ulid
    {
        return $this->id;
    }

    public function getType(): WorkoutType
    {
        return $this->type;
    }

    public function setType(WorkoutType $type): static
    {
        $this->type = $type;

        return $this;
    }

    public function getTitle(): string
    {
        return $this->title;
    }

    public function setTitle(string $title): static
    {
        $this->title = $title;

        return $this;
    }

    public function getStartedAt(): \DateTimeImmutable
    {
        return $this->startedAt;
    }

    public function setStartedAt(\DateTimeImmutable $startedAt): static
    {
        $this->startedAt = $startedAt;

        return $this;
    }

    public function getDurationMinutes(): int
    {
        return $this->durationMinutes;
    }

    public function setDurationMinutes(int $durationMinutes): static
    {
        $this->durationMinutes = $durationMinutes;

        return $this;
    }

    public function getDistanceMeters(): ?int
    {
        return $this->distanceMeters;
    }

    public function setDistanceMeters(?int $distanceMeters): static
    {
        $this->distanceMeters = $distanceMeters;

        return $this;
    }

    public function getKcal(): ?int
    {
        return $this->kcal;
    }

    public function setKcal(?int $kcal): static
    {
        $this->kcal = $kcal;

        return $this;
    }

    public function getAvgHeartRate(): ?int
    {
        return $this->avgHeartRate;
    }

    public function setAvgHeartRate(?int $avgHeartRate): static
    {
        $this->avgHeartRate = $avgHeartRate;

        return $this;
    }

    public function getFeeling(): ?WorkoutFeeling
    {
        return $this->feeling;
    }

    public function setFeeling(?WorkoutFeeling $feeling): static
    {
        $this->feeling = $feeling;

        return $this;
    }

    public function getNotes(): ?string
    {
        return $this->notes;
    }

    public function setNotes(?string $notes): static
    {
        $this->notes = $notes;

        return $this;
    }

    public function getSource(): WorkoutSource
    {
        return $this->source;
    }

    public function setSource(WorkoutSource $source): static
    {
        $this->source = $source;

        return $this;
    }

    public function getExternalId(): ?string
    {
        return $this->externalId;
    }

    public function setExternalId(?string $externalId): static
    {
        $this->externalId = $externalId;

        return $this;
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

    /** @return array<string, mixed> */
    public function toMercurePayload(?array $changedProperties = null): array
    {
        return self::filterPayload([
            'id' => (string) $this->id,
            'type' => $this->type->value,
            'title' => $this->title,
            'startedAt' => $this->startedAt->format(\DATE_ATOM),
            'durationMinutes' => $this->durationMinutes,
            'distanceMeters' => $this->distanceMeters,
            'kcal' => $this->kcal,
            'avgHeartRate' => $this->avgHeartRate,
            'feeling' => $this->feeling?->value,
            'notes' => $this->notes,
            'source' => $this->source->value,
            'externalId' => $this->externalId,
        ], $changedProperties);
    }
}
