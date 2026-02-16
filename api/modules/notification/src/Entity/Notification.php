<?php

declare(strict_types=1);

namespace Maggie\Notification\Entity;

use ApiPlatform\Doctrine\Orm\Filter\ExistsFilter;
use ApiPlatform\Metadata\ApiFilter;
use ApiPlatform\Metadata\ApiResource;
use ApiPlatform\Metadata\Delete;
use ApiPlatform\Metadata\Get;
use ApiPlatform\Metadata\GetCollection;
use ApiPlatform\Metadata\Patch;
use Maggie\Calendar\Contract\MercurePublishable;
use Maggie\Core\Contract\OwnedByUserInterface;
use Maggie\Core\Entity\User;
use Maggie\Notification\Repository\NotificationRepository;
use Maggie\Notification\State\CreateNotificationProcessor;
use Maggie\Notification\State\DeleteNotificationProcessor;
use Maggie\Notification\State\MarkReadProcessor;
use Doctrine\DBAL\Types\Types;
use Doctrine\ORM\Mapping as ORM;
use Symfony\Component\Uid\Ulid;

#[ORM\Entity(repositoryClass: NotificationRepository::class)]
#[ORM\Index(columns: ['user_id', 'read_at'], name: 'idx_notification_user_read')]
#[ORM\Index(columns: ['created_at'], name: 'idx_notification_created')]
#[ApiFilter(ExistsFilter::class, properties: ['readAt'])]
#[ApiResource(
    operations: [
        new GetCollection(order: ['createdAt' => 'DESC']),
        new Get(),
        new Patch(processor: MarkReadProcessor::class),
        new Delete(processor: DeleteNotificationProcessor::class),
    ],
)]
class Notification implements OwnedByUserInterface, MercurePublishable
{
    #[ORM\Id]
    #[ORM\Column(type: 'ulid')]
    private Ulid $id;

    #[ORM\ManyToOne(targetEntity: User::class)]
    #[ORM\JoinColumn(nullable: false)]
    private User $user;

    #[ORM\Column(length: 20, enumType: NotificationType::class)]
    private NotificationType $type;

    #[ORM\Column(length: 255)]
    private string $title;

    #[ORM\Column(type: Types::TEXT, nullable: true)]
    private ?string $body = null;

    #[ORM\Column(length: 500, nullable: true)]
    private ?string $relatedEntityIri = null;

    #[ORM\Column(type: Types::DATETIMETZ_IMMUTABLE, nullable: true)]
    private ?\DateTimeImmutable $readAt = null;

    #[ORM\Column(type: Types::DATETIMETZ_IMMUTABLE)]
    private \DateTimeImmutable $createdAt;

    public function __construct()
    {
        $this->id = new Ulid();
        $this->createdAt = new \DateTimeImmutable();
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

    public function getType(): NotificationType
    {
        return $this->type;
    }

    public function setType(NotificationType $type): static
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

    public function getBody(): ?string
    {
        return $this->body;
    }

    public function setBody(?string $body): static
    {
        $this->body = $body;

        return $this;
    }

    public function getRelatedEntityIri(): ?string
    {
        return $this->relatedEntityIri;
    }

    public function setRelatedEntityIri(?string $relatedEntityIri): static
    {
        $this->relatedEntityIri = $relatedEntityIri;

        return $this;
    }

    public function getReadAt(): ?\DateTimeImmutable
    {
        return $this->readAt;
    }

    public function setReadAt(?\DateTimeImmutable $readAt): static
    {
        $this->readAt = $readAt;

        return $this;
    }

    public function getCreatedAt(): \DateTimeImmutable
    {
        return $this->createdAt;
    }

    /** @return array<string, mixed> */
    public function toMercurePayload(): array
    {
        return [
            'id' => (string) $this->id,
            'type' => $this->type->value,
            'title' => $this->title,
            'body' => $this->body,
            'relatedEntityIri' => $this->relatedEntityIri,
            'readAt' => $this->readAt?->format('c'),
            'createdAt' => $this->createdAt->format('c'),
        ];
    }
}
