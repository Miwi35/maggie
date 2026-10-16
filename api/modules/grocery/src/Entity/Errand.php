<?php

declare(strict_types=1);

namespace Maggie\Grocery\Entity;

use ApiPlatform\Doctrine\Orm\Filter\DateFilter;
use ApiPlatform\Doctrine\Orm\Filter\OrderFilter;
use ApiPlatform\Metadata\ApiFilter;
use ApiPlatform\Metadata\ApiResource;
use ApiPlatform\Metadata\Get;
use ApiPlatform\Metadata\GetCollection;
use Doctrine\Common\Collections\ArrayCollection;
use Doctrine\Common\Collections\Collection;
use Doctrine\DBAL\Types\Types;
use Doctrine\ORM\Mapping as ORM;
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
use Maggie\Grocery\Repository\ErrandRepository;
use Symfony\Component\Uid\Ulid;

/**
 * A finished errand: what was bought, where and when (MAG-488). One per store —
 * ending the errand without naming a store archives one per store the checked
 * lines were in. Read-only: it is written by EndErrandHandler alone, and read by
 * the « Courses passées » views, the `list_past_errands` MCP tool and, later,
 * the « tu en prends souvent » suggestions.
 *
 * The store's name and each line's label are copied, not referenced only: a
 * past errand must still read « Lidl · Tomates » once the store or the product
 * is gone.
 */
#[ORM\Entity(repositoryClass: ErrandRepository::class)]
#[ORM\Index(columns: ['user_id', 'ended_at'], name: 'idx_errand_user_ended_at')]
#[ApiFilter(OrderFilter::class, properties: ['endedAt'])]
#[ApiFilter(DateFilter::class, properties: ['endedAt'])]
#[ApiFilter(UlidRelationFilter::class, properties: ['store'])]
#[Indexed(index: 'errands', module: 'grocery', globalSearch: false)]
#[ApiResource(
    operations: [
        new GetCollection(provider: ElasticsearchCollectionProvider::class),
        new Get(provider: ElasticsearchItemProvider::class),
    ],
    order: ['endedAt' => 'DESC'],
)]
class Errand implements MercurePublishable, OwnedByUserInterface, IndexableInterface
{
    use MercurePayloadFilterTrait;

    #[ORM\Id]
    #[ORM\Column(type: 'ulid')]
    private Ulid $id;

    #[ORM\ManyToOne(targetEntity: User::class)]
    #[ORM\JoinColumn(nullable: false, onDelete: 'CASCADE')]
    #[IndexedRelation(targetEntity: User::class, sourceField: 'userId')]
    private User $user;

    #[ORM\ManyToOne(targetEntity: Store::class)]
    #[ORM\JoinColumn(nullable: true, onDelete: 'SET NULL')]
    #[IndexedRelation(targetEntity: Store::class, sourceField: 'storeId')]
    private ?Store $store = null;

    /** The store's name when the errand ended; null for lines with no store. */
    #[ORM\Column(length: 255, nullable: true)]
    #[IndexedField(type: 'text', keyword: true)]
    private ?string $storeName = null;

    #[ORM\Column(type: Types::DATETIMETZ_IMMUTABLE)]
    #[IndexedField(type: 'date')]
    private \DateTimeImmutable $endedAt;

    #[ORM\Column(options: ['default' => 0])]
    #[IndexedField(type: 'integer')]
    private int $itemCount = 0;

    /** @var Collection<int, PurchasedItem> */
    #[ORM\OneToMany(targetEntity: PurchasedItem::class, mappedBy: 'errand', cascade: ['persist', 'remove'], orphanRemoval: true)]
    #[ORM\OrderBy(['position' => 'ASC'])]
    private Collection $items;

    public function __construct()
    {
        $this->id = new Ulid();
        $this->items = new ArrayCollection();
        $this->endedAt = new \DateTimeImmutable();
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

    public function getStore(): ?Store
    {
        return $this->store;
    }

    /** Sets the store and copies its name. */
    public function setStore(?Store $store): static
    {
        $this->store = $store;
        $this->storeName = $store?->getName();

        return $this;
    }

    public function getStoreName(): ?string
    {
        return $this->storeName;
    }

    public function getEndedAt(): \DateTimeImmutable
    {
        return $this->endedAt;
    }

    public function setEndedAt(\DateTimeImmutable $endedAt): static
    {
        $this->endedAt = $endedAt;

        return $this;
    }

    public function getItemCount(): int
    {
        return $this->itemCount;
    }

    /** @return Collection<int, PurchasedItem> */
    public function getItems(): Collection
    {
        return $this->items;
    }

    public function addItem(PurchasedItem $item): static
    {
        if (!$this->items->contains($item)) {
            $item->setErrand($this);
            $item->setPosition($this->items->count());
            $this->items->add($item);
            $this->itemCount = $this->items->count();
        }

        return $this;
    }

    /** @return array<string, mixed> */
    public function toSearchDocument(): array
    {
        return [
            'storeName' => $this->storeName,
            'endedAt' => $this->endedAt->format('c'),
            'itemCount' => $this->itemCount,
            'storeId' => null !== $this->store ? (string) $this->store->getId() : null,
            'userId' => (string) $this->user->getId(),
        ];
    }

    /** @return array<string, mixed> */
    public function toMercurePayload(?array $changedProperties = null): array
    {
        return self::filterPayload([
            'storeId' => null !== $this->store ? (string) $this->store->getId() : null,
            'storeName' => $this->storeName,
            'endedAt' => $this->endedAt->format(\DateTimeInterface::ATOM),
            'itemCount' => $this->itemCount,
            'items' => array_values($this->items->map(static fn (PurchasedItem $item) => $item->toPayload())->toArray()),
        ], $changedProperties);
    }
}
