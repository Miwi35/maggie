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
use Maggie\Core\Contract\IndexableInterface;
use Maggie\Core\Contract\OwnedByUserInterface;
use Maggie\Core\Elasticsearch\Attribute\Indexed;
use Maggie\Core\Elasticsearch\Attribute\IndexedField;
use Maggie\Core\Elasticsearch\Attribute\IndexedRelation;
use Maggie\Core\Elasticsearch\State\ElasticsearchCollectionProvider;
use Maggie\Core\Elasticsearch\State\ElasticsearchItemProvider;
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
#[Indexed(index: 'notifications', module: 'notification')]
#[ApiResource(
    operations: [
        new GetCollection(order: ['createdAt' => 'DESC'], provider: ElasticsearchCollectionProvider::class),
        new Get(provider: ElasticsearchItemProvider::class),
        new Patch(processor: MarkReadProcessor::class),
        new Delete(processor: DeleteNotificationProcessor::class),
    ],
)]
class Notification implements OwnedByUserInterface, MercurePublishable, IndexableInterface
{
    #[ORM\Id]
    #[ORM\Column(type: 'ulid')]
    private Ulid $id;

    #[ORM\ManyToOne(targetEntity: User::class)]
    #[ORM\JoinColumn(nullable: false)]
    #[IndexedRelation(targetEntity: User::class, sourceField: 'userId')]
    private User $user;

    #[ORM\Column(length: 20, enumType: NotificationType::class)]
    #[IndexedField(type: 'keyword')]
    private NotificationType $type;

    #[ORM\Column(length: 255)]
    #[IndexedField(type: 'text', boost: 2.0)]
    private string $title;

    #[ORM\Column(type: Types::TEXT, nullable: true)]
    #[IndexedField(type: 'text')]
    private ?string $body = null;

    #[ORM\Column(length: 500, nullable: true)]
    private ?string $relatedEntityIri = null;

    #[ORM\Column(type: Types::DATETIMETZ_IMMUTABLE, nullable: true)]
    #[IndexedField(type: 'date')]
    private ?\DateTimeImmutable $readAt = null;

    #[ORM\Column(type: Types::DATETIMETZ_IMMUTABLE)]
    #[IndexedField(type: 'date')]
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
    public function toSearchDocument(): array
    {
        return [
            'title' => $this->title,
            'body' => $this->body,
            'type' => $this->type->value,
            'createdAt' => $this->createdAt->format('c'),
            'readAt' => $this->readAt?->format('c'),
            'relatedEntityIri' => $this->relatedEntityIri,
            'userId' => (string) $this->user->getId(),
        ];
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
