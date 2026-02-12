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
use Maggie\Calendar\State\CreateTaskProcessor;
use Maggie\Calendar\State\DeleteTaskProcessor;
use Maggie\Calendar\State\UpdateTaskProcessor;
use Doctrine\DBAL\Types\Types;
use Doctrine\ORM\Mapping as ORM;
use Symfony\Component\Uid\Ulid;
use Symfony\Component\Validator\Constraints as Assert;

#[ORM\Entity(repositoryClass: TaskRepository::class)]
#[ORM\Index(columns: ['due_date'], name: 'idx_task_due_date')]
#[ORM\Index(columns: ['done_date'], name: 'idx_task_done_date')]
#[ApiFilter(DateFilter::class, properties: ['dueDate'])]
#[ApiFilter(ExistsFilter::class, properties: ['doneDate'])]
#[ApiFilter(SearchFilter::class, properties: ['priority' => 'exact', 'criticality' => 'exact'])]
#[ApiResource(operations: [
    new GetCollection(),
    new Get(),
    new Post(processor: CreateTaskProcessor::class),
    new Patch(processor: UpdateTaskProcessor::class),
    new Delete(processor: DeleteTaskProcessor::class),
])]
class Task implements MercurePublishable
{
    #[ORM\Id]
    #[ORM\Column(type: 'ulid')]
    private Ulid $id;

    #[ORM\Column(length: 255)]
    #[Assert\NotBlank]
    private string $name;

    #[ORM\Column(type: Types::TEXT, nullable: true)]
    private ?string $description = null;

    #[ORM\Column(length: 20, enumType: TaskPriority::class, options: ['default' => 'medium'])]
    private TaskPriority $priority = TaskPriority::Medium;

    #[ORM\Column(length: 20, enumType: TaskCriticality::class, options: ['default' => 'low'])]
    private TaskCriticality $criticality = TaskCriticality::Low;

    #[ORM\Column(type: Types::DATETIMETZ_IMMUTABLE, nullable: true)]
    private ?\DateTimeImmutable $dueDate = null;

    #[ORM\Column(type: Types::DATETIMETZ_IMMUTABLE, nullable: true)]
    private ?\DateTimeImmutable $doneDate = null;

    public function __construct()
    {
        $this->id = new Ulid();
    }

    public function getId(): Ulid
    {
        return $this->id;
    }

    public function getName(): string
    {
        return $this->name;
    }

    public function setName(string $name): static
    {
        $this->name = $name;

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

    public function getDoneDate(): ?\DateTimeImmutable
    {
        return $this->doneDate;
    }

    public function setDoneDate(?\DateTimeImmutable $doneDate): static
    {
        $this->doneDate = $doneDate;

        return $this;
    }

    public function isDone(): bool
    {
        return $this->doneDate !== null;
    }

    public function toMercurePayload(): array
    {
        return [
            'name' => $this->name,
            'priority' => $this->priority->value,
            'criticality' => $this->criticality->value,
            'dueDate' => $this->dueDate?->format('c'),
            'isDone' => $this->isDone(),
        ];
    }
}
