<?php

namespace Maggie\Calendar\Entity;

use ApiPlatform\Doctrine\Orm\Filter\DateFilter;
use ApiPlatform\Doctrine\Orm\Filter\ExistsFilter;
use ApiPlatform\Doctrine\Orm\Filter\SearchFilter;
use ApiPlatform\Metadata\ApiFilter;
use ApiPlatform\Metadata\ApiResource;
use ApiPlatform\Metadata\Delete;
use ApiPlatform\Metadata\Get;
use ApiPlatform\Metadata\GetCollection;
use ApiPlatform\Metadata\Patch;
use ApiPlatform\Metadata\Post;
use Maggie\Calendar\Contract\MercurePublishable;
use Maggie\Calendar\Repository\TaskRepository;
use Maggie\Calendar\Trait\HasGoogleTaskTrackingTrait;
use Maggie\Core\Contract\IndexableInterface;
use Maggie\Core\Contract\OwnedByUserInterface;
use Maggie\Core\Elasticsearch\Attribute\Indexed;
use Maggie\Core\Elasticsearch\Attribute\IndexedField;
use Maggie\Core\Elasticsearch\Attribute\IndexedRelation;
use Maggie\Core\Elasticsearch\State\ElasticsearchCollectionProvider;
use Maggie\Core\Elasticsearch\State\ElasticsearchItemProvider;
use Maggie\Core\Entity\User;
use Maggie\Calendar\State\CreateTaskProcessor;
use Maggie\Calendar\State\DeleteTaskProcessor;
use Maggie\Calendar\State\UpdateTaskProcessor;
use Doctrine\DBAL\Types\Types;
use Doctrine\ORM\Mapping as ORM;
use Symfony\Component\Uid\Ulid;
use Symfony\Component\Validator\Constraints as Assert;

#[ORM\Entity(repositoryClass: TaskRepository::class)]
#[ORM\Index(columns: ['due_date'], name: 'idx_task_due_date')]
#[ORM\Index(columns: ['completed_at'], name: 'idx_task_completed_at')]
#[ApiFilter(DateFilter::class, properties: ['dueDate'])]
#[ApiFilter(ExistsFilter::class, properties: ['completedAt', 'dueDate'])]
#[ApiFilter(SearchFilter::class, properties: ['priority' => 'exact', 'criticality' => 'exact'])]
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

    #[ORM\Column(type: Types::DATETIMETZ_IMMUTABLE, nullable: true)]
    #[IndexedField(type: 'date')]
    private ?\DateTimeImmutable $dueDate = null;

    #[ORM\Column(type: Types::DATETIMETZ_IMMUTABLE, nullable: true)]
    #[IndexedField(type: 'date')]
    private ?\DateTimeImmutable $completedAt = null;

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

    public function isDone(): bool
    {
        return $this->completedAt !== null;
    }

    /** @return array<string, mixed> */
    public function toSearchDocument(): array
    {
        return [
            'title' => $this->title,
            'description' => $this->description,
            'dueDate' => $this->dueDate?->format('c'),
            'completedAt' => $this->completedAt?->format('c'),
            'priority' => $this->priority->value,
            'criticality' => $this->criticality->value,
            'userId' => (string) $this->user->getId(),
        ];
    }

    public function toMercurePayload(): array
    {
        return [
            'title' => $this->title,
            'priority' => $this->priority->value,
            'criticality' => $this->criticality->value,
            'dueDate' => $this->dueDate?->format('c'),
            'isDone' => $this->isDone(),
        ];
    }
}
