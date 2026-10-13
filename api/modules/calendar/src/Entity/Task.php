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
use Maggie\Calendar\Enum\TaskPriority;
use Maggie\Calendar\Repository\TaskRepository;
use Maggie\Calendar\State\CreateTaskProcessor;
use Maggie\Calendar\State\DeleteTaskProcessor;
use Maggie\Calendar\State\UpdateTaskProcessor;
use Maggie\Calendar\Trait\HasGoogleTaskTrackingTrait;
use Maggie\Core\Contract\IndexableInterface;
use Maggie\Core\Contract\MercurePublishable;
use Maggie\Core\Contract\OwnedByUserInterface;
use Maggie\Core\Elasticsearch\Attribute\Indexed;
use Maggie\Core\Elasticsearch\Attribute\IndexedField;
use Maggie\Core\Elasticsearch\Attribute\IndexedRelation;
use Maggie\Core\Elasticsearch\State\ElasticsearchCollectionProvider;
use Maggie\Core\Elasticsearch\State\ElasticsearchItemProvider;
use Maggie\Core\Entity\User;
use Maggie\Core\Doctrine\Filter\UlidRelationFilter;
use Maggie\Core\Mercure\Trait\MercurePayloadFilterTrait;
use Maggie\Core\Serializer\StrictDayNormalizer;
use Symfony\Component\Serializer\Attribute\Context;
use Symfony\Component\Serializer\Normalizer\DateTimeNormalizer;
use Symfony\Component\Uid\Ulid;
use Symfony\Component\Validator\Constraints as Assert;

#[ORM\Entity(repositoryClass: TaskRepository::class)]
#[ORM\Index(columns: ['due_date'], name: 'idx_task_due_date')]
#[ORM\Index(columns: ['completed_at'], name: 'idx_task_completed_at')]
#[ApiFilter(DateFilter::class, properties: ['dueDate'])]
#[ApiFilter(ExistsFilter::class, properties: ['completedAt', 'dueDate'])]
#[ApiFilter(SearchFilter::class, properties: ['priority' => 'exact', 'criticality' => 'exact'])]
#[ApiFilter(OrderFilter::class, properties: ['id', 'dueDate', 'criticality', 'position'])]
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

    #[ORM\Id]
    #[ORM\Column(type: 'ulid')]
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
    #[ApiProperty(description: 'The order among the tasks of the same parent, compared as a string. Left out on creation, the task goes last.')]
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
            'completedAt' => $this->completedAt?->format('c'),
            'parentId' => null !== $this->parent ? (string) $this->parent->getId() : null,
            'position' => $this->position,
            'links' => $this->links,
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
            'isDone' => $this->isDone(),
            'parent' => null !== $this->parent ? '/api/tasks/'.$this->parent->getId() : null,
            'position' => $this->position,
            'links' => $this->links,
        ], $changedProperties, ['completedAt' => 'isDone']);
    }
}
