<?php

declare(strict_types=1);

namespace Maggie\Grocery\Entity;

use ApiPlatform\Doctrine\Orm\Filter\OrderFilter;
use ApiPlatform\Metadata\ApiFilter;
use ApiPlatform\Metadata\ApiProperty;
use ApiPlatform\Metadata\ApiResource;
use ApiPlatform\Metadata\Delete;
use ApiPlatform\Metadata\Get;
use ApiPlatform\Metadata\GetCollection;
use ApiPlatform\Metadata\Patch;
use ApiPlatform\Metadata\Post;
use Doctrine\ORM\Mapping as ORM;
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
use Maggie\Grocery\Enum\ProductCategory;
use Maggie\Grocery\Enum\Unit;
use Maggie\Grocery\Exception\InvalidPackagingException;
use Maggie\Grocery\Repository\ProductRepository;
use Maggie\Grocery\State\CreateProductProcessor;
use Maggie\Grocery\State\DeleteProductProcessor;
use Maggie\Grocery\State\UpdateProductProcessor;
use Symfony\Component\Uid\Ulid;
use Symfony\Component\Validator\Constraints as Assert;

#[ORM\Entity(repositoryClass: ProductRepository::class)]
#[ORM\InheritanceType('SINGLE_TABLE')]
#[ORM\DiscriminatorColumn(name: 'dtype', type: 'string', length: 20)]
#[ORM\DiscriminatorMap(['product' => Product::class])]
#[ApiFilter(OrderFilter::class, properties: ['name'])]
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
    use MercurePayloadFilterTrait;

    // The admin sends a record back with `id` set to its IRI; the id is never
    // writable, so it must not be parsed as a Ulid on the way in.
    #[ORM\Id]
    #[ORM\Column(type: 'ulid')]
    #[ApiProperty(writable: false)]
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
    #[IndexedRelation(targetEntity: Store::class, sourceField: 'preferredStoreId')]
    private ?Store $preferredStore = null;

    #[ORM\ManyToOne(targetEntity: Store::class)]
    #[ORM\JoinColumn(nullable: true, onDelete: 'SET NULL')]
    #[IndexedRelation(targetEntity: Store::class, sourceField: 'fallbackStoreId')]
    private ?Store $fallbackStore = null;

    #[ORM\Column(type: 'integer', nullable: true)]
    private ?int $shelfLifeDays = null;

    // What the product is bought in: « paquet de 500 g » is (pack, 500, g),
    // « bocal » is (jar, null, null). Size and size unit go together.
    #[ORM\Column(length: 20, nullable: true, enumType: Unit::class)]
    private ?Unit $packagingUnit = null;

    #[ORM\Column(type: 'float', nullable: true)]
    private ?float $packagingSize = null;

    #[ORM\Column(length: 20, nullable: true, enumType: Unit::class)]
    private ?Unit $packagingSizeUnit = null;

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

    public function getPackagingUnit(): ?Unit
    {
        return $this->packagingUnit;
    }

    public function setPackagingUnit(?Unit $packagingUnit): static
    {
        $this->packagingUnit = $packagingUnit;

        return $this;
    }

    public function getPackagingSize(): ?float
    {
        return $this->packagingSize;
    }

    public function setPackagingSize(?float $packagingSize): static
    {
        $this->packagingSize = $packagingSize;

        return $this;
    }

    public function getPackagingSizeUnit(): ?Unit
    {
        return $this->packagingSizeUnit;
    }

    public function setPackagingSizeUnit(?Unit $packagingSizeUnit): static
    {
        $this->packagingSizeUnit = $packagingSizeUnit;

        return $this;
    }

    /**
     * The handlers call this once every field is applied: the API and the
     * MCP tools share the rule, and a PATCH may set the fields one by one.
     *
     * @throws InvalidPackagingException
     */
    public function assertPackagingIsConsistent(): void
    {
        if (null !== $this->packagingSize && $this->packagingSize <= 0) {
            throw new InvalidPackagingException('packagingSize must be greater than 0.');
        }
        if ((null === $this->packagingSize) !== (null === $this->packagingSizeUnit)) {
            throw new InvalidPackagingException('packagingSize and packagingSizeUnit go together: set both or neither.');
        }
        if (null !== $this->packagingSize && null === $this->packagingUnit) {
            throw new InvalidPackagingException('packagingSize needs a packagingUnit: say what the size is the content of.');
        }
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
            'preferredStoreId' => null !== $this->preferredStore ? (string) $this->preferredStore->getId() : null,
            'fallbackStoreId' => null !== $this->fallbackStore ? (string) $this->fallbackStore->getId() : null,
            'shelfLifeDays' => $this->shelfLifeDays,
            'packagingUnit' => $this->packagingUnit?->value,
            'packagingSize' => $this->packagingSize,
            'packagingSizeUnit' => $this->packagingSizeUnit?->value,
        ];
    }

    public function toMercurePayload(?array $changedProperties = null): array
    {
        return self::filterPayload([
            'name' => $this->name,
            'category' => $this->category->value,
            'kcalPer100g' => $this->kcalPer100g,
            'proteinPer100g' => $this->proteinPer100g,
            'carbsPer100g' => $this->carbsPer100g,
            'fatPer100g' => $this->fatPer100g,
            'preferredStoreId' => null !== $this->preferredStore ? (string) $this->preferredStore->getId() : null,
            'fallbackStoreId' => null !== $this->fallbackStore ? (string) $this->fallbackStore->getId() : null,
            'shelfLifeDays' => $this->shelfLifeDays,
            'packagingUnit' => $this->packagingUnit?->value,
            'packagingSize' => $this->packagingSize,
            'packagingSizeUnit' => $this->packagingSizeUnit?->value,
        ], $changedProperties, ['preferredStore' => 'preferredStoreId', 'fallbackStore' => 'fallbackStoreId']);
    }
}
