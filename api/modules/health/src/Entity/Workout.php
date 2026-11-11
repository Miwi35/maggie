<?php

declare(strict_types=1);

namespace Maggie\Health\Entity;

use ApiPlatform\Doctrine\Orm\Filter\DateFilter;
use ApiPlatform\Doctrine\Orm\Filter\OrderFilter;
use ApiPlatform\Doctrine\Orm\Filter\SearchFilter;
use ApiPlatform\Metadata\ApiFilter;
use ApiPlatform\Metadata\ApiProperty;
use ApiPlatform\Metadata\ApiResource;
use ApiPlatform\Metadata\Delete;
use ApiPlatform\Metadata\Get;
use ApiPlatform\Metadata\GetCollection;
use ApiPlatform\Metadata\Patch;
use ApiPlatform\Metadata\Post;
use Doctrine\Common\Collections\ArrayCollection;
use Doctrine\Common\Collections\Collection;
use Doctrine\DBAL\Types\Types;
use Doctrine\ORM\Mapping as ORM;
use Maggie\Calendar\Entity\Event;
use Maggie\Core\Contract\MercurePublishable;
use Maggie\Core\Contract\OwnedByUserInterface;
use Maggie\Core\Doctrine\Filter\UlidRelationFilter;
use Maggie\Core\Entity\User;
use Maggie\Core\Mercure\Trait\MercurePayloadFilterTrait;
use Maggie\Health\Dto\WorkoutExerciseInput;
use Maggie\Health\Enum\MakeupOption;
use Maggie\Health\Enum\WorkoutFeeling;
use Maggie\Health\Enum\WorkoutSource;
use Maggie\Health\Enum\WorkoutType;
use Maggie\Health\Enum\WorkoutVariant;
use Maggie\Health\Filter\WorkoutExerciseFilter;
use Maggie\Health\Repository\WorkoutRepository;
use Maggie\Health\State\CreateWorkoutProcessor;
use Maggie\Health\State\DeleteWorkoutProcessor;
use Maggie\Health\State\UpdateWorkoutProcessor;
use Symfony\Bridge\Doctrine\Validator\Constraints\UniqueEntity;
use Symfony\Component\Serializer\Attribute\Context;
use Symfony\Component\Serializer\Attribute\Ignore;
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
// A planned session is kept by one session done at most (MAG-771).
#[ORM\UniqueConstraint(name: 'uniq_workout_keeps', columns: ['keeps_id'])]
#[UniqueEntity(fields: ['user', 'externalId'], message: 'This externalId is already used by another workout.', ignoreNull: 'externalId')]
#[ApiFilter(SearchFilter::class, properties: ['type' => 'exact', 'source' => 'exact'])]
#[ApiFilter(UlidRelationFilter::class, properties: ['event'])]
#[ApiFilter(DateFilter::class, properties: ['startedAt'])]
#[ApiFilter(WorkoutExerciseFilter::class, properties: ['exercise'])]
#[ApiFilter(OrderFilter::class, properties: ['startedAt'])]
#[ApiResource(operations: [
    new GetCollection(uriTemplate: '/workouts', order: ['startedAt' => 'DESC'], paginationMaximumItemsPerPage: 500),
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

    public const MIN_DIFFICULTY = 1;

    public const MAX_DIFFICULTY = 10;

    /** Doctrine property → payload key, for {@see self::filterPayload()}. */
    private const PAYLOAD_KEYS = ['exerciseItems' => 'exercises'];

    // The admin sends the record back with its IRI as `id`: never denormalized.
    #[ORM\Id]
    #[ORM\Column(type: 'ulid')]
    #[ApiProperty(writable: false)]
    private Ulid $id;

    #[ORM\Column(length: 16, enumType: WorkoutType::class)]
    #[Assert\NotNull]
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
    #[Assert\NotNull]
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

    #[ORM\Column(type: 'smallint', nullable: true)]
    #[ApiProperty(description: 'How hard the whole session was, from 1 to 10; a session done only (refused on a planned one).')]
    private ?int $difficulty = null;

    #[ORM\Column(length: 16, nullable: true, enumType: WorkoutVariant::class)]
    #[ApiProperty(description: 'A coach session\'s kind: normal, calibration, soft or deload.')]
    private ?WorkoutVariant $variant = null;

    // Set by the rule, never written by a client (MAG-771): the planned session
    // this session done keeps, and the streak once it did.
    #[ORM\ManyToOne(targetEntity: self::class)]
    #[ORM\JoinColumn(nullable: true, onDelete: 'SET NULL')]
    #[ApiProperty(description: 'The planned session this session done keeps (read only, set by Maggie).', writable: false)]
    private ?Workout $keeps = null;

    #[ORM\Column(type: 'integer', nullable: true)]
    #[ApiProperty(description: 'The streak of planned sessions kept in a row once this one kept its own (read only; null when it keeps none).', writable: false)]
    private ?int $streak = null;

    #[ORM\ManyToOne(targetEntity: self::class)]
    #[ORM\JoinColumn(nullable: true, onDelete: 'SET NULL')]
    #[ApiProperty(description: 'The missed planned session this planned make-up replaces (read only).', writable: false)]
    private ?Workout $makeupOf = null;

    #[ORM\Column(length: 16, nullable: true, enumType: MakeupOption::class)]
    #[ApiProperty(description: 'How the make-up was chosen: softer, shorter or tomorrow (read only).', writable: false)]
    private ?MakeupOption $makeupOption = null;

    /** @var Collection<int, WorkoutExercise> */
    #[ORM\OneToMany(targetEntity: WorkoutExercise::class, mappedBy: 'workout', cascade: ['persist', 'remove'], orphanRemoval: true)]
    #[ORM\OrderBy(['position' => 'ASC'])]
    private Collection $exerciseItems;

    /**
     * The `exercises` a request sent, as sent: the write path validates them
     * ({@see \Maggie\Health\Service\WorkoutRules::exercises()}). Null: not sent.
     *
     * @var list<mixed>|null
     */
    private ?array $requestedExercises = null;

    // The planned session's event in the « Santé » module agenda (MAG-777), filed by
    // {@see \Maggie\Health\UseCase\FilePlannedWorkoutInAgenda}: never written by a client.
    #[ORM\OneToOne(targetEntity: Event::class)]
    #[ORM\JoinColumn(nullable: true, onDelete: 'SET NULL')]
    #[ApiProperty(writable: false, description: 'The event of a planned session in the « Santé » agenda (IRI); null for a session done.')]
    private ?Event $event = null;

    #[ORM\ManyToOne(targetEntity: User::class)]
    #[ORM\JoinColumn(nullable: false, onDelete: 'CASCADE')]
    private User $user;

    public function __construct()
    {
        $this->id = new Ulid();
        $this->exerciseItems = new ArrayCollection();
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

    public function getDifficulty(): ?int
    {
        return $this->difficulty;
    }

    public function setDifficulty(?int $difficulty): static
    {
        $this->difficulty = $difficulty;

        return $this;
    }

    public function getKeeps(): ?Workout
    {
        return $this->keeps;
    }

    public function setKeeps(?Workout $keeps): static
    {
        $this->keeps = $keeps;

        return $this;
    }

    public function getStreak(): ?int
    {
        return $this->streak;
    }

    public function setStreak(?int $streak): static
    {
        $this->streak = $streak;

        return $this;
    }

    public function getMakeupOf(): ?Workout
    {
        return $this->makeupOf;
    }

    public function setMakeupOf(?Workout $makeupOf): static
    {
        $this->makeupOf = $makeupOf;

        return $this;
    }

    public function getMakeupOption(): ?MakeupOption
    {
        return $this->makeupOption;
    }

    public function setMakeupOption(?MakeupOption $makeupOption): static
    {
        $this->makeupOption = $makeupOption;

        return $this;
    }

    #[Ignore]
    public function isPlanned(): bool
    {
        return WorkoutSource::Planned === $this->source;
    }

    /** When it ends (or ended): its start plus its duration. */
    public function endsAt(): \DateTimeImmutable
    {
        return $this->startedAt->modify(\sprintf('+%d minutes', $this->durationMinutes));
    }

    public static function iri(Workout $workout): string
    {
        return '/api/workouts/'.$workout->getId();
    }

    public function getVariant(): ?WorkoutVariant
    {
        return $this->variant;
    }

    public function setVariant(?WorkoutVariant $variant): static
    {
        $this->variant = $variant;

        return $this;
    }

    /**
     * The session's exercises, in order (MAG-775): what is prescribed, the sets
     * done and how each felt; `name` and `unit` are the catalogue's.
     *
     * @return list<array<string, mixed>>
     */
    #[ApiProperty(
        description: 'The exercises, in order, always written whole (the list sent replaces the previous one). Each: exercise (catalogue slug), step, sets, target, targetMin, targetMax, loadKg (only if the exercise is loadable), restSeconds, tempo, targetRir (0 to 4), cue, feeling (too_easy, right, too_hard; done session only), done (the sets done: {reps|seconds|meters, loadKg?}, in the exercise\'s unit; done session only). name and unit are read only, from the catalogue.',
        schema: [
            'type' => 'array',
            'items' => [
                'type' => 'object',
                'required' => ['exercise', 'step', 'sets', 'target', 'targetMin', 'targetMax', 'restSeconds'],
                'properties' => [
                    'exercise' => ['type' => 'string'],
                    'name' => ['type' => 'string', 'readOnly' => true],
                    'unit' => ['type' => 'string', 'enum' => ['reps', 'seconds', 'meters'], 'readOnly' => true],
                    'step' => ['type' => 'integer', 'minimum' => 1],
                    'sets' => ['type' => 'integer', 'minimum' => 1],
                    'target' => ['type' => 'integer', 'minimum' => 1],
                    'targetMin' => ['type' => 'integer', 'minimum' => 1],
                    'targetMax' => ['type' => 'integer', 'minimum' => 1],
                    'loadKg' => ['type' => ['number', 'null'], 'minimum' => 0],
                    'restSeconds' => ['type' => 'integer', 'minimum' => 0],
                    'tempo' => ['type' => 'boolean'],
                    'targetRir' => ['type' => ['integer', 'null'], 'minimum' => 0, 'maximum' => 4],
                    'cue' => ['type' => ['string', 'null']],
                    'feeling' => ['type' => ['string', 'null'], 'enum' => ['too_easy', 'right', 'too_hard', null]],
                    'done' => [
                        'type' => 'array',
                        'items' => [
                            'type' => 'object',
                            'properties' => [
                                'reps' => ['type' => 'integer', 'minimum' => 0],
                                'seconds' => ['type' => 'integer', 'minimum' => 0],
                                'meters' => ['type' => 'integer', 'minimum' => 0],
                                'loadKg' => ['type' => 'number', 'minimum' => 0],
                            ],
                        ],
                    ],
                ],
            ],
        ],
    )]
    public function getExercises(): array
    {
        return array_map(static fn (WorkoutExercise $item): array => $item->toArray(), $this->exerciseList());
    }

    /** @param list<mixed> $exercises */
    public function setExercises(array $exercises): static
    {
        $this->requestedExercises = $exercises;

        return $this;
    }

    /** @return list<mixed>|null what the request sent as `exercises`, null when it sent none */
    public function requestedExercises(): ?array
    {
        return $this->requestedExercises;
    }

    /** @return list<WorkoutExercise> */
    public function exerciseList(): array
    {
        $items = $this->exerciseItems->getValues();
        usort($items, static fn (WorkoutExercise $a, WorkoutExercise $b): int => $a->getPosition() <=> $b->getPosition());

        return $items;
    }

    /**
     * Replaces the whole list: the rows are recreated, nothing is merged.
     *
     * @param list<WorkoutExerciseInput> $exercises
     */
    public function replaceExercises(array $exercises): static
    {
        $this->exerciseItems->clear();
        foreach ($exercises as $index => $input) {
            $this->exerciseItems->add(new WorkoutExercise($this, $index + 1, $input));
        }
        $this->requestedExercises = null;

        return $this;
    }

    public function getEvent(): ?Event
    {
        return $this->event;
    }

    public function setEvent(?Event $event): static
    {
        $this->event = $event;

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
            'difficulty' => $this->difficulty,
            'variant' => $this->variant?->value,
            'keeps' => null === $this->keeps ? null : self::iri($this->keeps),
            'streak' => $this->streak,
            'makeupOf' => null === $this->makeupOf ? null : self::iri($this->makeupOf),
            'makeupOption' => $this->makeupOption?->value,
            'exercises' => $this->getExercises(),
            'event' => null === $this->event ? null : '/api/events/'.$this->event->getId(),
        ], $changedProperties, self::PAYLOAD_KEYS);
    }
}
