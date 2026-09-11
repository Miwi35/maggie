<?php

declare(strict_types=1);

namespace Maggie\Grocery\Entity;

use ApiPlatform\Metadata\ApiResource;
use ApiPlatform\Metadata\Delete;
use ApiPlatform\Metadata\Get;
use ApiPlatform\Metadata\GetCollection;
use ApiPlatform\Metadata\Patch;
use ApiPlatform\Metadata\Post;
use Maggie\Grocery\Enum\RecurringFrequency;
use Maggie\Grocery\Enum\Unit;
use Maggie\Grocery\Repository\RecurringGroceryItemRepository;
use Maggie\Grocery\State\CreateRecurringGroceryItemProcessor;
use Maggie\Grocery\State\DeleteRecurringGroceryItemProcessor;
use Maggie\Grocery\State\UpdateRecurringGroceryItemProcessor;
use Maggie\Core\Contract\IndexableInterface;
use Maggie\Core\Contract\MercurePublishable;
use Maggie\Core\Contract\OwnedByUserInterface;
use Maggie\Core\Elasticsearch\Attribute\Indexed;
use Maggie\Core\Elasticsearch\Attribute\IndexedField;
use Maggie\Core\Elasticsearch\Attribute\IndexedRelation;
use Maggie\Core\Elasticsearch\State\ElasticsearchCollectionProvider;
use Maggie\Core\Elasticsearch\State\ElasticsearchItemProvider;
use Maggie\Core\Entity\User;
use Maggie\Core\Mercure\Trait\MercurePayloadFilterTrait;
use Doctrine\ORM\Mapping as ORM;
use Symfony\Component\Uid\Ulid;

#[ORM\Entity(repositoryClass: RecurringGroceryItemRepository::class)]
#[Indexed(index: 'recurring_grocery_items', module: 'grocery')]
#[ApiResource(operations: [
    new GetCollection(provider: ElasticsearchCollectionProvider::class),
    new Get(provider: ElasticsearchItemProvider::class),
    new Post(processor: CreateRecurringGroceryItemProcessor::class),
    new Patch(processor: UpdateRecurringGroceryItemProcessor::class),
    new Delete(processor: DeleteRecurringGroceryItemProcessor::class),
])]
class RecurringGroceryItem implements OwnedByUserInterface, IndexableInterface, MercurePublishable
{
    use MercurePayloadFilterTrait;

    #[ORM\Id]
    #[ORM\Column(type: 'ulid')]
    private Ulid $id;

    #[ORM\ManyToOne(targetEntity: Product::class)]
    #[ORM\JoinColumn(nullable: true)]
    #[IndexedRelation(targetEntity: Product::class, sourceField: 'productId')]
    private ?Product $product = null;

    #[ORM\Column(length: 255, nullable: true)]
    #[IndexedField(type: 'text')]
    private ?string $customLabel = null;

    #[ORM\Column(type: 'float', nullable: true)]
    #[IndexedField(type: 'float')]
    private ?float $quantity = null;

    #[ORM\Column(length: 20, nullable: true, enumType: Unit::class)]
    #[IndexedField(type: 'keyword')]
    private ?Unit $unit = null;

    #[ORM\Column(length: 20, enumType: RecurringFrequency::class)]
    #[IndexedField(type: 'keyword')]
    private RecurringFrequency $frequency;

    #[ORM\ManyToOne(targetEntity: User::class)]
    #[ORM\JoinColumn(nullable: false)]
    #[IndexedRelation(targetEntity: User::class, sourceField: 'userId')]
    private User $user;

    public function __construct()
    {
        $this->id = new Ulid();
    }

    public function getId(): Ulid
    {
        return $this->id;
    }

    public function getProduct(): ?Product
    {
        return $this->product;
    }

    public function setProduct(?Product $product): static
    {
        $this->product = $product;

        return $this;
    }

    public function getCustomLabel(): ?string
    {
        return $this->customLabel;
    }

    public function setCustomLabel(?string $customLabel): static
    {
        $this->customLabel = $customLabel;

        return $this;
    }

    public function getLabel(): string
    {
        return $this->customLabel ?? $this->product?->getName() ?? 'Unknown';
    }

    public function getQuantity(): ?float
    {
        return $this->quantity;
    }

    public function setQuantity(?float $quantity): static
    {
        $this->quantity = $quantity;

        return $this;
    }

    public function getUnit(): ?Unit
    {
        return $this->unit;
    }

    public function setUnit(?Unit $unit): static
    {
        $this->unit = $unit;

        return $this;
    }

    public function getFrequency(): RecurringFrequency
    {
        return $this->frequency;
    }

    public function setFrequency(RecurringFrequency $frequency): static
    {
        $this->frequency = $frequency;

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
    public function toSearchDocument(): array
    {
        return [
            'customLabel' => $this->customLabel,
            'frequency' => $this->frequency->value,
            'quantity' => $this->quantity,
            'unit' => $this->unit?->value,
            'userId' => (string) $this->user->getId(),
            'productId' => $this->product !== null ? (string) $this->product->getId() : null,
        ];
    }

    /** @return array<string, mixed> */
    public function toMercurePayload(?array $changedProperties = null): array
    {
        return self::filterPayload([
            'label' => $this->getLabel(),
            'customLabel' => $this->customLabel,
            'quantity' => $this->quantity,
            'unit' => $this->unit?->value,
            'frequency' => $this->frequency->value,
            'productId' => $this->product !== null ? (string) $this->product->getId() : null,
        ], $changedProperties);
    }
}
