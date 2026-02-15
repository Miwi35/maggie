<?php

declare(strict_types=1);

namespace Maggie\Memory\Entity;

use ApiPlatform\Metadata\ApiResource;
use ApiPlatform\Metadata\Delete;
use ApiPlatform\Metadata\Get;
use ApiPlatform\Metadata\GetCollection;
use ApiPlatform\Metadata\Patch;
use Maggie\Core\Contract\OwnedByUserInterface;
use Maggie\Core\Entity\User;
use Maggie\Memory\Repository\MemoryRepository;
use Doctrine\DBAL\Types\Types;
use Doctrine\ORM\Mapping as ORM;
use Symfony\Component\Uid\Ulid;

#[ORM\Entity(repositoryClass: MemoryRepository::class)]
#[ORM\Index(columns: ['type'], name: 'idx_memory_type')]
#[ORM\Index(columns: ['user_id', 'type'], name: 'idx_memory_user_type')]
#[ORM\Index(columns: ['created_at'], name: 'idx_memory_created_at')]
#[ApiResource(operations: [
    new GetCollection(),
    new Get(),
    new Patch(),
    new Delete(),
])]
class Memory implements OwnedByUserInterface
{
    #[ORM\Id]
    #[ORM\Column(type: 'ulid')]
    private Ulid $id;

    #[ORM\ManyToOne(targetEntity: User::class)]
    #[ORM\JoinColumn(nullable: false)]
    private User $user;

    #[ORM\Column(length: 20, enumType: MemoryType::class)]
    private MemoryType $type;

    #[ORM\Column(type: Types::TEXT)]
    private string $content;

    #[ORM\Column(type: Types::JSON, nullable: true)]
    private ?array $metadata = null;

    #[ORM\Column(type: Types::DATETIMETZ_IMMUTABLE)]
    private \DateTimeImmutable $createdAt;

    #[ORM\Column(type: Types::DATETIMETZ_IMMUTABLE)]
    private \DateTimeImmutable $updatedAt;

    public function __construct()
    {
        $this->id = new Ulid();
        $this->createdAt = new \DateTimeImmutable();
        $this->updatedAt = new \DateTimeImmutable();
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

    public function getType(): MemoryType
    {
        return $this->type;
    }

    public function setType(MemoryType $type): static
    {
        $this->type = $type;
        return $this;
    }

    public function getContent(): string
    {
        return $this->content;
    }

    public function setContent(string $content): static
    {
        $this->content = $content;
        $this->updatedAt = new \DateTimeImmutable();
        return $this;
    }

    /** @return array<string, mixed>|null */
    public function getMetadata(): ?array
    {
        return $this->metadata;
    }

    /** @param array<string, mixed>|null $metadata */
    public function setMetadata(?array $metadata): static
    {
        $this->metadata = $metadata;
        return $this;
    }

    public function getCreatedAt(): \DateTimeImmutable
    {
        return $this->createdAt;
    }

    public function getUpdatedAt(): \DateTimeImmutable
    {
        return $this->updatedAt;
    }
}
