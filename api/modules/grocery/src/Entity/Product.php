<?php

declare(strict_types=1);

namespace Maggie\Grocery\Entity;

use ApiPlatform\Metadata\ApiResource;
use ApiPlatform\Metadata\Delete;
use ApiPlatform\Metadata\Get;
use ApiPlatform\Metadata\GetCollection;
use ApiPlatform\Metadata\Patch;
use ApiPlatform\Metadata\Post;
use Maggie\Core\Contract\MercurePublishable;
use Maggie\Grocery\Enum\ProductCategory;
use Maggie\Grocery\Enum\Unit;
use Maggie\Grocery\Repository\ProductRepository;
use Maggie\Grocery\State\CreateProductProcessor;
use Maggie\Grocery\State\DeleteProductProcessor;
use Maggie\Grocery\State\UpdateProductProcessor;
use Maggie\Core\Contract\IndexableInterface;
use Maggie\Core\Contract\OwnedByUserInterface;
use Maggie\Core\Elasticsearch\Attribute\Indexed;
use Maggie\Core\Elasticsearch\Attribute\IndexedField;
use Maggie\Core\Elasticsearch\Attribute\IndexedRelation;
use Maggie\Core\Elasticsearch\State\ElasticsearchCollectionProvider;
use Maggie\Core\Elasticsearch\State\ElasticsearchItemProvider;
use Maggie\Core\Entity\User;
use Doctrine\ORM\Mapping as ORM;
use Symfony\Component\Uid\Ulid;
use Symfony\Component\Validator\Constraints as Assert;

#[ORM\Entity(repositoryClass: ProductRepository::class)]
#[ORM\InheritanceType("SINGLE_TABLE")]
#[ORM\DiscriminatorColumn(name: "dtype", type: "string", length: 20)]
#[ORM\DiscriminatorMap(["product" => Product::class])]
#[Indexed(index: 'products', module: 'grocery')]
#[ApiResource(operations: [
    new GetCollection(provider: ElasticsearchCollectionProvider::class),
    new Get(provider: ElasticsearchItemProvider::class),
    new Post(processor: CreateProductProcessor::class),
    new Patch(processor: UpdateProductProcessor::class),
    new Delete(processor: DeleteProductProcessor::class),
])]
class Product implements MercurePublishable, OwnedByUserInterface, IndexableInterface
{
    #[ORM\Id]
    #[ORM\Column(type: 'ulid')]
    private Ulid $id;

    #[ORM\Column(length: 255)]
    #[Assert\NotBlank]
    #[IndexedField(type: 'text', boost: 3.0, keyword: true)]
    private string $name;

    #[ORM\Column(length: 20, nullable: true, enumType: Unit::class)]
    #[IndexedField(type: 'keyword')]
    private ?Unit $defaultUnit = null;

    #[ORM\Column(length: 20, enumType: ProductCategory::class)]
    #[Assert\NotNull]
    #[IndexedField(type: 'keyword')]
    private ProductCategory $category;

    #[ORM\ManyToOne(targetEntity: User::class)]
    #[ORM\JoinColumn(nullable: false)]
    #[IndexedRelation(targetEntity: User::class, sourceField: 'userId')]
    private User $user;

    #[ORM\Column(length: 10, nullable: true)]
    private ?string $ciqualAlimCode = null;

    #[ORM\Column(type: 'float', nullable: true)]
    private ?float $kcalPer100g = null;

    #[ORM\Column(type: 'float', nullable: true)]
    private ?float $proteinPer100g = null;

    #[ORM\Column(type: 'float', nullable: true)]
    private ?float $carbsPer100g = null;

    #[ORM\Column(type: 'float', nullable: true)]
    private ?float $fatPer100g = null;

    #[ORM\ManyToOne(targetEntity: Store::class)]
    #[ORM\JoinColumn(nullable: true, onDelete: 'SET NULL')]
    private ?Store $preferredStore = null;

    #[ORM\ManyToOne(targetEntity: Store::class)]
    #[ORM\JoinColumn(nullable: true, onDelete: 'SET NULL')]
    private ?Store $fallbackStore = null;

    #[ORM\Column(type: 'integer', nullable: true)]
    private ?int $shelfLifeDays = null;

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

    public function getDefaultUnit(): ?Unit
    {
        return $this->defaultUnit;
    }

    public function setDefaultUnit(?Unit $defaultUnit): static
    {
        $this->defaultUnit = $defaultUnit;

        return $this;
    }

    public function getCategory(): ProductCategory
    {
        return $this->category;
    }

    public function setCategory(ProductCategory $category): static
    {
        $this->category = $category;

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

    public function getCiqualAlimCode(): ?string
    {
        return $this->ciqualAlimCode;
    }

    public function setCiqualAlimCode(?string $ciqualAlimCode): static
    {
        $this->ciqualAlimCode = $ciqualAlimCode;

        return $this;
    }

    public function getKcalPer100g(): ?float
    {
        return $this->kcalPer100g;
    }

    public function setKcalPer100g(?float $kcalPer100g): static
    {
        $this->kcalPer100g = $kcalPer100g;

        return $this;
    }

    public function getProteinPer100g(): ?float
    {
        return $this->proteinPer100g;
    }

    public function setProteinPer100g(?float $proteinPer100g): static
    {
        $this->proteinPer100g = $proteinPer100g;

        return $this;
    }

    public function getCarbsPer100g(): ?float
    {
        return $this->carbsPer100g;
    }

    public function setCarbsPer100g(?float $carbsPer100g): static
    {
        $this->carbsPer100g = $carbsPer100g;

        return $this;
    }

    public function getFatPer100g(): ?float
    {
        return $this->fatPer100g;
    }

    public function setFatPer100g(?float $fatPer100g): static
    {
        $this->fatPer100g = $fatPer100g;

        return $this;
    }

    public function getPreferredStore(): ?Store
    {
        return $this->preferredStore;
    }

    public function setPreferredStore(?Store $preferredStore): static
    {
        $this->preferredStore = $preferredStore;

        return $this;
    }

    public function getFallbackStore(): ?Store
    {
        return $this->fallbackStore;
    }

    public function setFallbackStore(?Store $fallbackStore): static
    {
        $this->fallbackStore = $fallbackStore;

        return $this;
    }

    public function getShelfLifeDays(): ?int
    {
        return $this->shelfLifeDays;
    }

    public function setShelfLifeDays(?int $shelfLifeDays): static
    {
        $this->shelfLifeDays = $shelfLifeDays;

        return $this;
    }

    /** @return array<string, mixed> */
    public function toSearchDocument(): array
    {
        return [
            'name' => $this->name,
            'category' => $this->category->value,
            'defaultUnit' => $this->defaultUnit?->value,
            'userId' => (string) $this->user->getId(),
            'dtype' => strtolower((new \ReflectionClass($this))->getShortName()),
            'kcalPer100g' => $this->kcalPer100g,
            'proteinPer100g' => $this->proteinPer100g,
            'carbsPer100g' => $this->carbsPer100g,
            'fatPer100g' => $this->fatPer100g,
            'preferredStoreId' => $this->preferredStore !== null ? (string) $this->preferredStore->getId() : null,
            'fallbackStoreId' => $this->fallbackStore !== null ? (string) $this->fallbackStore->getId() : null,
            'shelfLifeDays' => $this->shelfLifeDays,
        ];
    }

    public function toMercurePayload(): array
    {
        return [
            'name' => $this->name,
            'category' => $this->category->value,
            'kcalPer100g' => $this->kcalPer100g,
            'proteinPer100g' => $this->proteinPer100g,
            'carbsPer100g' => $this->carbsPer100g,
            'fatPer100g' => $this->fatPer100g,
            'preferredStoreId' => $this->preferredStore !== null ? (string) $this->preferredStore->getId() : null,
            'fallbackStoreId' => $this->fallbackStore !== null ? (string) $this->fallbackStore->getId() : null,
            'shelfLifeDays' => $this->shelfLifeDays,
        ];
    }
}
