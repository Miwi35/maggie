<?php

namespace Maggie\Calendar\Entity;

use ApiPlatform\Doctrine\Orm\Filter\DateFilter;
use ApiPlatform\Doctrine\Orm\Filter\ExistsFilter;
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
use Doctrine\DBAL\Types\Types;
use Doctrine\ORM\Mapping as ORM;
use Maggie\Calendar\Enum\TaskCriticality;
use Maggie\Calendar\Enum\TaskMoment;
use Maggie\Calendar\Enum\TaskPriority;
use Maggie\Calendar\Enum\TaskUrgencyLevel;
use Maggie\Calendar\Filter\CompletionPeriodFilter;
use Maggie\Calendar\Repository\TaskRepository;
use Maggie\Calendar\Service\TaskSchedule;
use Maggie\Calendar\State\CreateTaskProcessor;
use Maggie\Calendar\State\DeleteTaskProcessor;
use Maggie\Calendar\State\UpdateTaskProcessor;
use Maggie\Calendar\Trait\HasGoogleTaskTrackingTrait;
use Maggie\Core\Contract\IndexableInterface;
use Maggie\Core\Contract\MercurePublishable;
use Maggie\Core\Contract\OwnedByUserInterface;
use Maggie\Core\Doctrine\Filter\UlidRelationFilter;
use Maggie\Core\Elasticsearch\Attribute\Indexed;
use Maggie\Core\Elasticsearch\Attribute\IndexedField;
use Maggie\Core\Elasticsearch\Attribute\IndexedRelation;
use Maggie\Core\Elasticsearch\State\ElasticsearchCollectionProvider;
use Maggie\Core\Elasticsearch\State\ElasticsearchItemProvider;
use Maggie\Core\Entity\User;
use Maggie\Core\Mercure\Trait\MercurePayloadFilterTrait;
use Maggie\Core\Serializer\StrictDayNormalizer;
use Symfony\Component\Serializer\Attribute\Context;
use Symfony\Component\Serializer\Normalizer\DateTimeNormalizer;
use Symfony\Component\Uid\Ulid;
use Symfony\Component\Validator\Constraints as Assert;

#[ORM\Entity(repositoryClass: TaskRepository::class)]
#[ORM\Index(columns: ['due_date'], name: 'idx_task_due_date')]
#[ORM\Index(columns: ['completed_at'], name: 'idx_task_completed_at')]
#[ORM\Index(columns: ['scheduled_date'], name: 'idx_task_scheduled_date')]
#[ApiFilter(DateFilter::class, properties: ['dueDate', 'scheduledDate'])]
#[ApiFilter(CompletionPeriodFilter::class, properties: ['completedAt'])]
#[ApiFilter(ExistsFilter::class, properties: ['completedAt', 'dueDate', 'scheduledDate'])]
#[ApiFilter(SearchFilter::class, properties: ['priority' => 'exact', 'criticality' => 'exact'])]
#[ApiFilter(OrderFilter::class, properties: ['id', 'dueDate', 'criticality', 'position', 'completedAt'])]
#[ApiFilter(UlidRelationFilter::class, properties: ['parent'])]
#[Indexed(index: 'tasks', module: 'calendar')]
#[ApiResource(operations: [
    new GetCollection(provider: ElasticsearchCollectionProvider::class),
    new Get(provider: ElasticsearchItemProvider::class),
    new Post(processor: CreateTaskProcessor::class),
    new Patch(processor: UpdateTaskProcessor::class),
    new Delete(processor: DeleteTaskProcessor::class),
])]
class Task implements MercurePublishable, OwnedByUserInterface, IndexableInterface
{
    use HasGoogleTaskTrackingTrait;
    use MercurePayloadFilterTrait;

    public const MAX_ESTIMATED_MINUTES = 1440;

    // The admin sends the record back with its IRI as `id`: never denormalized.
    #[ORM\Id]
    #[ORM\Column(type: 'ulid')]
    #[ApiProperty(writable: false)]
    private Ulid $id;

    #[ORM\ManyToOne(targetEntity: User::class)]
    #[ORM\JoinColumn(nullable: false)]
    #[IndexedRelation(targetEntity: User::class, sourceField: 'userId')]
    private User $user;

    #[ORM\Column(length: 255)]
    #[Assert\NotBlank]
    #[IndexedField(type: 'text', boost: 3.0)]
    private string $title;

    #[ORM\Column(type: Types::TEXT, nullable: true)]
    #[IndexedField(type: 'text')]
    private ?string $description = null;

    #[ORM\Column(length: 20, enumType: TaskPriority::class, options: ['default' => 'medium'])]
    #[IndexedField(type: 'keyword')]
    private TaskPriority $priority = TaskPriority::Medium;

    #[ORM\Column(length: 20, enumType: TaskCriticality::class, options: ['default' => 'low'])]
    #[IndexedField(type: 'keyword')]
    private TaskCriticality $criticality = TaskCriticality::Low;

    /**
     * A day, as Google Tasks has it: its API keeps no time of day (MAG-404).
     * The index keeps the default date formats, which a day goes through.
     */
    #[ORM\Column(type: Types::DATE_IMMUTABLE, nullable: true)]
    #[IndexedField(type: 'date')]
    #[ApiProperty(
        description: 'The day the task is due, YYYY-MM-DD.',
        openapiContext: ['type' => ['string', 'null'], 'format' => 'date', 'example' => '2037-01-01'],
    )]
    #[Context(
        normalizationContext: [DateTimeNormalizer::FORMAT_KEY => 'Y-m-d'],
        denormalizationContext: [DateTimeNormalizer::FORMAT_KEY => StrictDayNormalizer::DAY_FORMAT],
    )]
    private ?\DateTimeImmutable $dueDate = null;

    /**
     * The day the owner plans to do it (MAG-718), not a deadline: it puts the
     * task in that day's agenda and in « Aujourd'hui ». Maggie's own: Google
     * Tasks only keeps the due day.
     */
    #[ORM\Column(type: Types::DATE_IMMUTABLE, nullable: true)]
    #[IndexedField(type: 'date')]
    #[ApiProperty(
        description: 'The day the task is planned to be done, YYYY-MM-DD — not its deadline (dueDate). Puts it in that day\'s agenda and in today\'s tasks. Maggie\'s own: Google never sees it.',
        openapiContext: ['type' => ['string', 'null'], 'format' => 'date', 'example' => '2037-01-01'],
    )]
    #[Context(
        normalizationContext: [DateTimeNormalizer::FORMAT_KEY => 'Y-m-d'],
        denormalizationContext: [DateTimeNormalizer::FORMAT_KEY => StrictDayNormalizer::DAY_FORMAT],
    )]
    private ?\DateTimeImmutable $scheduledDate = null;

    /** When in the scheduled day: a part of it, or `time` with `scheduledTime`. Null: just the day. */
    #[ORM\Column(length: 20, nullable: true, enumType: TaskMoment::class)]
    #[IndexedField(type: 'keyword')]
    #[ApiProperty(
        description: 'When in the scheduled day: morning, afternoon, evening, or time (then scheduledTime is required). Null: the day only. Needs a scheduledDate.',
        openapiContext: ['type' => ['string', 'null'], 'enum' => ['morning', 'afternoon', 'evening', 'time', null]],
    )]
    private ?TaskMoment $scheduledMoment = null;

    /** `HH:MM` in the owner's timezone, set if and only if the moment is `time`. */
    #[ORM\Column(length: 5, nullable: true)]
    #[IndexedField(type: 'keyword')]
    #[Assert\Regex(pattern: TaskSchedule::TIME_PATTERN, message: 'scheduledTime must be a time "HH:MM".')]
    #[ApiProperty(
        description: 'The time of the scheduled day, HH:MM in the user\'s timezone. Only with scheduledMoment "time", and required with it.',
        openapiContext: ['type' => ['string', 'null'], 'pattern' => '^([01]\d|2[0-3]):[0-5]\d$', 'example' => '18:00'],
    )]
    private ?string $scheduledTime = null;

    /**
     * Minutes before `scheduledTime` a reminder goes out, as an event has them
     * (0: at the time). Only at a precise time.
     *
     * @var list<int>
     */
    #[ORM\Column(type: Types::JSON, options: ['default' => '[]'])]
    #[IndexedField(type: 'integer')]
    #[Assert\Count(max: Event::MAX_REMINDERS)]
    #[Assert\All([new Assert\Type('integer'), new Assert\Range(min: 0, max: TaskSchedule::MAX_REMINDER_MINUTES)])]
    #[ApiProperty(
        description: 'Reminders, in minutes before scheduledTime (0: at the time, up to 10080: a week). Only with scheduledMoment "time"; set to time without reminders, it gets one at the time.',
        openapiContext: ['type' => 'array', 'items' => ['type' => 'integer', 'minimum' => 0, 'maximum' => TaskSchedule::MAX_REMINDER_MINUTES], 'maxItems' => Event::MAX_REMINDERS],
    )]
    private array $reminders = [];

    #[ORM\Column(type: Types::DATETIMETZ_IMMUTABLE, nullable: true)]
    #[IndexedField(type: 'date')]
    private ?\DateTimeImmutable $completedAt = null;

    /** The task this one is a subtask of. One level only, as on Google: a subtask has no subtask. */
    #[ORM\ManyToOne(targetEntity: self::class)]
    #[ORM\JoinColumn(nullable: true, onDelete: 'CASCADE')]
    #[IndexedRelation(targetEntity: self::class, sourceField: 'parentId')]
    #[ApiProperty(description: 'The task this one is a subtask of. A subtask cannot have subtasks.')]
    private ?self $parent = null;

    /**
     * Google's manual order among the siblings: a string compared as is,
     * twenty digits on Google. Left empty, a new task goes after its siblings.
     */
    #[ORM\Column(length: 64, nullable: true)]
    #[IndexedField(type: 'keyword')]
    #[Assert\Length(max: 64)]
    #[ApiProperty(description: 'The order among the tasks of the same parent, compared as a string. Left out on creation, the task goes last. Writing a sibling\'s position swaps the two: that is how a task moves up or down.')]
    private ?string $position = null;

    /**
     * What Google attached to the task (an email it came from, a document),
     * read from Google and never written back: its API cannot.
     *
     * @var list<array{type: string, description: string, link: string}>
     */
    #[ORM\Column(type: Types::JSON, options: ['default' => '[]'])]
    #[IndexedField(type: 'object', properties: [
        'type' => ['type' => 'keyword'],
        'description' => ['type' => 'text'],
        'link' => ['type' => 'keyword', 'index' => false],
    ])]
    #[ApiProperty(writable: false, description: 'Links Google attached to the task, read only: {type, description, link}.')]
    private array $links = [];

    /** How long the owner thinks it takes, in minutes. Maggie's own: never sent to Google nor read from it (MAG-497). */
    #[ORM\Column(nullable: true)]
    #[IndexedField(type: 'integer')]
    #[Assert\Range(min: 1, max: self::MAX_ESTIMATED_MINUTES)]
    #[ApiProperty(
        description: 'How long the task should take, in minutes (1 to 1440). Maggie\'s own: Google never sees it.',
        openapiContext: ['type' => ['integer', 'null'], 'minimum' => 1, 'maximum' => self::MAX_ESTIMATED_MINUTES],
    )]
    private ?int $estimatedMinutes = null;

    /** The first small gesture that starts the task. Maggie's own, like `estimatedMinutes`. */
    #[ORM\Column(length: 255, nullable: true)]
    #[IndexedField(type: 'text')]
    #[Assert\Length(max: 255)]
    #[ApiProperty(description: 'The first small step that starts the task. Maggie\'s own: Google never sees it.')]
    private ?string $firstStep = null;

    /**
     * Computed on each read, never stored nor indexed: it depends on the day
     * (MAG-405). {@see \Maggie\Calendar\Serializer\TaskComputedFieldsNormalizer}
     */
    private ?int $overdueDays = null;

    /** Computed on each read like `overdueDays` (MAG-406). {@see \Maggie\Calendar\Service\TaskUrgency} */
    private ?TaskUrgencyLevel $urgency = null;

    /** Computed on each read like `overdueDays` (MAG-406). {@see \Maggie\Calendar\Service\TaskUrgency} */
    private ?int $orderScore = null;

    /** Computed on each read like `overdueDays` (MAG-497). {@see \Maggie\Calendar\Service\TaskPostponement} */
    private ?\DateTimeImmutable $postponedSince = null;

    public function __construct()
    {
        $this->id = new Ulid();
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

    public function getTitle(): string
    {
        return $this->title;
    }

    public function setTitle(string $title): static
    {
        $this->title = $title;

        return $this;
    }

    public function getDescription(): ?string
    {
        return $this->description;
    }

    public function setDescription(?string $description): static
    {
        $this->description = $description;

        return $this;
    }

    public function getPriority(): TaskPriority
    {
        return $this->priority;
    }

    public function setPriority(TaskPriority $priority): static
    {
        $this->priority = $priority;

        return $this;
    }

    public function getCriticality(): TaskCriticality
    {
        return $this->criticality;
    }

    public function setCriticality(TaskCriticality $criticality): static
    {
        $this->criticality = $criticality;

        return $this;
    }

    public function getDueDate(): ?\DateTimeImmutable
    {
        return $this->dueDate;
    }

    public function setDueDate(?\DateTimeImmutable $dueDate): static
    {
        $this->dueDate = $dueDate;

        return $this;
    }

    public function getScheduledDate(): ?\DateTimeImmutable
    {
        return $this->scheduledDate;
    }

    public function setScheduledDate(?\DateTimeImmutable $scheduledDate): static
    {
        $this->scheduledDate = $scheduledDate;

        return $this;
    }

    public function getScheduledMoment(): ?TaskMoment
    {
        return $this->scheduledMoment;
    }

    public function setScheduledMoment(?TaskMoment $scheduledMoment): static
    {
        $this->scheduledMoment = $scheduledMoment;

        return $this;
    }

    public function getScheduledTime(): ?string
    {
        return $this->scheduledTime;
    }

    public function setScheduledTime(?string $scheduledTime): static
    {
        $this->scheduledTime = $scheduledTime;

        return $this;
    }

    /** @return list<int> */
    public function getReminders(): array
    {
        return $this->reminders;
    }

    /** @param list<int> $reminders */
    public function setReminders(array $reminders): static
    {
        $this->reminders = $reminders;

        return $this;
    }

    /**
     * The four fields of the programmation at once, as {@see TaskSchedule}
     * settled them: they only make sense together.
     *
     * @param list<int> $reminders
     */
    public function schedule(?\DateTimeImmutable $day, ?TaskMoment $moment, ?string $time, array $reminders): static
    {
        $this->scheduledDate = $day;
        $this->scheduledMoment = $moment;
        $this->scheduledTime = $time;
        $this->reminders = $reminders;

        return $this;
    }

    public function getCompletedAt(): ?\DateTimeImmutable
    {
        return $this->completedAt;
    }

    public function setCompletedAt(?\DateTimeImmutable $completedAt): static
    {
        $this->completedAt = $completedAt;

        return $this;
    }

    public function getParent(): ?self
    {
        return $this->parent;
    }

    public function setParent(?self $parent): static
    {
        $this->parent = $parent;

        return $this;
    }

    public function getPosition(): ?string
    {
        return $this->position;
    }

    public function setPosition(?string $position): static
    {
        $this->position = $position;

        return $this;
    }

    /** @return list<array{type: string, description: string, link: string}> */
    public function getLinks(): array
    {
        return $this->links;
    }

    /** @param list<array{type: string, description: string, link: string}> $links */
    public function setLinks(array $links): static
    {
        $this->links = $links;

        return $this;
    }

    public function getEstimatedMinutes(): ?int
    {
        return $this->estimatedMinutes;
    }

    public function setEstimatedMinutes(?int $estimatedMinutes): static
    {
        $this->estimatedMinutes = $estimatedMinutes;

        return $this;
    }

    public function getFirstStep(): ?string
    {
        return $this->firstStep;
    }

    public function setFirstStep(?string $firstStep): static
    {
        $this->firstStep = $firstStep;

        return $this;
    }

    #[ApiProperty(
        writable: false,
        description: 'The day the task has been put off since: its due day once past, or the first past day it was planned and not done. Null when done or not put off. Shown as « reportée », never as a reproach. Computed on read.',
        openapiContext: ['type' => ['string', 'null'], 'format' => 'date', 'example' => '2037-01-01'],
    )]
    #[Context(normalizationContext: [DateTimeNormalizer::FORMAT_KEY => 'Y-m-d'])]
    public function getPostponedSince(): ?\DateTimeImmutable
    {
        return $this->postponedSince;
    }

    public function setPostponedSince(?\DateTimeImmutable $postponedSince): static
    {
        $this->postponedSince = $postponedSince;

        return $this;
    }

    #[ApiProperty(
        writable: false,
        description: 'Days late: the due day is past and the task is not done. Null otherwise. Computed on read, in the user\'s timezone.',
        openapiContext: ['type' => ['integer', 'null'], 'minimum' => 1],
    )]
    public function getOverdueDays(): ?int
    {
        return $this->overdueDays;
    }

    public function setOverdueDays(?int $overdueDays): static
    {
        $this->overdueDays = $overdueDays;

        return $this;
    }

    #[ApiProperty(
        writable: false,
        description: 'How urgent the task is: low, normal, high or critical, from its criticality and how close or how far past its due day is. Computed on read, never entered.',
        openapiContext: ['type' => 'string', 'enum' => ['low', 'normal', 'high', 'critical']],
    )]
    public function getUrgency(): ?TaskUrgencyLevel
    {
        return $this->urgency;
    }

    public function setUrgency(?TaskUrgencyLevel $urgency): static
    {
        $this->urgency = $urgency;

        return $this;
    }

    #[ApiProperty(
        writable: false,
        description: 'The order score: time pressure + criticality + priority, with the user\'s weights. The highest first. Computed on read.',
        openapiContext: ['type' => 'integer', 'minimum' => 0],
    )]
    public function getOrderScore(): ?int
    {
        return $this->orderScore;
    }

    public function setOrderScore(?int $orderScore): static
    {
        $this->orderScore = $orderScore;

        return $this;
    }

    public function isDone(): bool
    {
        return null !== $this->completedAt;
    }

    /** @return array<string, mixed> */
    public function toSearchDocument(): array
    {
        return [
            'title' => $this->title,
            'description' => $this->description,
            'dueDate' => $this->dueDate?->format('Y-m-d'),
            'scheduledDate' => $this->scheduledDate?->format('Y-m-d'),
            'scheduledMoment' => $this->scheduledMoment?->value,
            'scheduledTime' => $this->scheduledTime,
            'reminders' => $this->reminders,
            'completedAt' => $this->completedAt?->format('c'),
            'parentId' => null !== $this->parent ? (string) $this->parent->getId() : null,
            'position' => $this->position,
            'links' => $this->links,
            'estimatedMinutes' => $this->estimatedMinutes,
            'firstStep' => $this->firstStep,
            'priority' => $this->priority->value,
            'criticality' => $this->criticality->value,
            'userId' => (string) $this->user->getId(),
        ];
    }

    public function toMercurePayload(?array $changedProperties = null): array
    {
        return self::filterPayload([
            'title' => $this->title,
            'priority' => $this->priority->value,
            'criticality' => $this->criticality->value,
            'dueDate' => $this->dueDate?->format('Y-m-d'),
            'scheduledDate' => $this->scheduledDate?->format('Y-m-d'),
            'scheduledMoment' => $this->scheduledMoment?->value,
            'scheduledTime' => $this->scheduledTime,
            'reminders' => $this->reminders,
            'isDone' => $this->isDone(),
            'parent' => null !== $this->parent ? '/api/tasks/'.$this->parent->getId() : null,
            'position' => $this->position,
            'links' => $this->links,
            'estimatedMinutes' => $this->estimatedMinutes,
            'firstStep' => $this->firstStep,
        ], $changedProperties, ['completedAt' => 'isDone']);
    }
}
