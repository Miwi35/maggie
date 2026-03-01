<?php

declare(strict_types=1);

namespace Maggie\Grocery\Entity;

use ApiPlatform\Metadata\ApiResource;
use ApiPlatform\Metadata\Get;
use ApiPlatform\Metadata\GetCollection;
use Maggie\Core\Contract\MercurePublishable;
use Maggie\Grocery\Repository\GroceryListRepository;
use Maggie\Core\Contract\IndexableInterface;
use Maggie\Core\Contract\OwnedByUserInterface;
use Maggie\Core\Elasticsearch\Attribute\Indexed;
use Maggie\Core\Elasticsearch\Attribute\IndexedRelation;
use Maggie\Core\Elasticsearch\State\ElasticsearchCollectionProvider;
use Maggie\Core\Elasticsearch\State\ElasticsearchItemProvider;
use Maggie\Core\Entity\User;
use Doctrine\Common\Collections\ArrayCollection;
use Doctrine\Common\Collections\Collection;
use Doctrine\DBAL\Types\Types;
use Doctrine\ORM\Mapping as ORM;
use Symfony\Component\Uid\Ulid;

#[ORM\Entity(repositoryClass: GroceryListRepository::class)]
#[ORM\UniqueConstraint(name: 'uniq_grocery_list_user', columns: ['user_id'])]
#[Indexed(index: 'grocery_lists', module: 'grocery')]
#[ApiResource(operations: [
    new GetCollection(provider: ElasticsearchCollectionProvider::class),
    new Get(provider: ElasticsearchItemProvider::class),
])]
class GroceryList implements MercurePublishable, OwnedByUserInterface, IndexableInterface
{
    #[ORM\Id]
    #[ORM\Column(type: 'ulid')]
    private Ulid $id;

    #[ORM\ManyToOne(targetEntity: User::class)]
    #[ORM\JoinColumn(nullable: false)]
    #[IndexedRelation(targetEntity: User::class, sourceField: 'userId')]
    private User $user;

    /** @var Collection<int, GroceryItem> */
    #[ORM\OneToMany(targetEntity: GroceryItem::class, mappedBy: 'groceryList', cascade: ['persist', 'remove'], orphanRemoval: true)]
    private Collection $items;

    #[ORM\Column(type: Types::DATETIMETZ_IMMUTABLE)]
    private \DateTimeImmutable $createdAt;

    #[ORM\Column(type: Types::DATETIMETZ_IMMUTABLE)]
    private \DateTimeImmutable $updatedAt;

    public function __construct()
    {
        $this->id = new Ulid();
        $this->items = new ArrayCollection();
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

    /** @return Collection<int, GroceryItem> */
    public function getItems(): Collection
    {
        return $this->items;
    }

    public function addItem(GroceryItem $item): static
    {
        if (!$this->items->contains($item)) {
            $this->items->add($item);
            $item->setGroceryList($this);
        }

        return $this;
    }

    public function removeItem(GroceryItem $item): static
    {
        $this->items->removeElement($item);

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

    public function setUpdatedAt(\DateTimeImmutable $updatedAt): static
    {
        $this->updatedAt = $updatedAt;

        return $this;
    }

    /** @return array<string, mixed> */
    public function toSearchDocument(): array
    {
        return [
            'createdAt' => $this->createdAt->format('c'),
            'updatedAt' => $this->updatedAt->format('c'),
            'userId' => (string) $this->user->getId(),
            'items' => $this->items->map(fn (GroceryItem $item) => [
                'productId' => $item->getProduct() !== null ? (string) $item->getProduct()->getId() : null,
                'customLabel' => $item->getCustomLabel(),
                'quantity' => $item->getQuantity(),
                'unit' => $item->getUnit()?->value,
                'checked' => $item->isChecked(),
                'source' => $item->getSource()->value,
                'storeId' => $item->getStore() !== null ? (string) $item->getStore()->getId() : null,
                'buyAfter' => $item->getBuyAfter()?->format('Y-m-d'),
                'position' => $item->getPosition(),
            ])->toArray(),
        ];
    }

    public function toMercurePayload(): array
    {
        return [
            'itemCount' => $this->items->count(),
        ];
    }
}
