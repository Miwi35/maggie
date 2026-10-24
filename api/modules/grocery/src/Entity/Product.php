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
use Doctrine\Common\Collections\ArrayCollection;
use Doctrine\Common\Collections\Collection;
use Doctrine\DBAL\Types\Types;
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
use Maggie\Grocery\Enum\ProductStockState;
use Maggie\Grocery\Enum\Unit;
use Maggie\Grocery\Exception\InvalidPackagingException;
use Maggie\Grocery\Exception\InvalidSeasonMonthsException;
use Maggie\Grocery\Exception\InvalidStockException;
use Maggie\Grocery\Repository\ProductRepository;
use Maggie\Grocery\Service\SeasonCalendar;
use Maggie\Grocery\State\CreateProductProcessor;
use Maggie\Grocery\State\DeleteProductProcessor;
use Maggie\Grocery\State\UpdateProductProcessor;
use Symfony\Component\Serializer\Attribute\Context;
use Symfony\Component\Serializer\Attribute\Ignore;
use Symfony\Component\Serializer\Normalizer\DateTimeNormalizer;
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
    // writable, so it must not be parsed as a Ulid on the way in. Protected, not
    // private: an Ingredient is a Product row, and API Platform reads the
    // attribute through the subclass, which cannot see a private property.
    #[ORM\Id]
    #[ORM\Column(type: 'ulid')]
    #[ApiProperty(writable: false)]
    protected Ulid $id;

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

    // What is left of it at home. Never null: a product always has a state,
    // « En stock » until someone says otherwise.
    #[ORM\Column(length: 20, enumType: ProductStockState::class, options: ['default' => 'in_stock'])]
    #[IndexedField(type: 'keyword')]
    private ProductStockState $stockState = ProductStockState::InStock;

    // How many packagings to buy when it runs out (rice: 2 packs).
    #[ORM\Column(type: 'integer', nullable: true)]
    #[IndexedField(type: 'integer')]
    private ?int $restockQuantity = null;

    /**
     * Whether running out puts it back on the list by itself.
     *
     * Named without the `is` on purpose: Symfony serialises `isFoo()` as
     * `foo`, so a field declared `isAutoRestock` would be `autoRestock` over
     * REST and `isAutoRestock` on Mercure — the disagreement `isCushion`
     * already pays for. One spelling, every channel.
     */
    #[ORM\Column(name: 'is_auto_restock', type: 'boolean', options: ['default' => false])]
    #[IndexedField(type: 'boolean')]
    private bool $autoRestock = false;

    /**
     * Its aisle in each store, one at most per store (MAG-493). Read and
     * written as `aisles`: this collection is the storage, never serialised.
     *
     * @var Collection<int, ProductAisle>
     */
    #[ORM\OneToMany(targetEntity: ProductAisle::class, mappedBy: 'product', cascade: ['persist', 'remove'], orphanRemoval: true)]
    #[ApiProperty(readable: false, writable: false)]
    #[Ignore]
    private Collection $aisleAssignments;

    /**
     * What a PATCH sent as `aisles`, null when it sent nothing: the processor
     * hands it to the command, whose handler checks and applies it.
     *
     * @var list<array{store: string, aisle: ?string}>|null
     */
    #[ApiProperty(readable: false, writable: false)]
    #[Ignore]
    private ?array $aislesInput = null;

    /**
     * The months (1 to 12) the product is in season in metropolitan France,
     * sorted; null for a product without a season — anything that is not a
     * fruit or a vegetable. Pre-filled from the seasonality calendar when the
     * product is created (MAG-494), the user may correct it.
     *
     * @var list<int>|null
     */
    #[ORM\Column(type: 'json', nullable: true)]
    #[IndexedField(type: 'integer')]
    #[ApiProperty(description: 'Months (1 = January … 12 = December) the product is in season in metropolitan France; null for a product without a season.')]
    private ?array $seasonMonths = null;

    /**
     * Computed on each read, never stored nor indexed: it depends on the month
     * of the user's timezone. {@see \Maggie\Grocery\Serializer\SeasonNormalizer}
     */
    #[ApiProperty(writable: false, description: 'Whether the product is in season this month, in the user\'s timezone; null for a product without a season.')]
    private ?bool $inSeason = null;

    /** The month $inSeason was computed for; null until something marks it. */
    private ?int $seasonMonth = null;

    /**
     * « Pas besoin » on its « tu en prends souvent » suggestion: not offered
     * again before this day (MAG-495). Read-only from the API: it is written
     * by `POST /api/grocery-suggestions/{productId}/snooze` alone.
     */
    #[ORM\Column(type: Types::DATE_IMMUTABLE, nullable: true)]
    #[IndexedField(type: 'date', format: 'yyyy-MM-dd')]
    #[ApiProperty(writable: false, openapiContext: ['type' => 'string', 'format' => 'date', 'example' => '2026-10-27'])]
    #[Context(normalizationContext: [DateTimeNormalizer::FORMAT_KEY => 'Y-m-d'])]
    private ?\DateTimeImmutable $suggestionSnoozedUntil = null;

    public function __construct()
    {
        $this->id = new Ulid();
        $this->aisleAssignments = new ArrayCollection();
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

    public function getStockState(): ProductStockState
    {
        return $this->stockState;
    }

    public function setStockState(ProductStockState $stockState): static
    {
        $this->stockState = $stockState;

        return $this;
    }

    /** @throws InvalidStockException */
    public static function parseStockState(string $value): ProductStockState
    {
        return ProductStockState::tryFrom($value)
            ?? throw new InvalidStockException(sprintf('Unknown stock state "%s". Use in_stock, low or out.', $value));
    }

    public function getRestockQuantity(): ?int
    {
        return $this->restockQuantity;
    }

    public function setRestockQuantity(?int $restockQuantity): static
    {
        $this->restockQuantity = $restockQuantity;

        return $this;
    }

    public function isAutoRestock(): bool
    {
        return $this->autoRestock;
    }

    public function setAutoRestock(bool $autoRestock): static
    {
        $this->autoRestock = $autoRestock;

        return $this;
    }

    /** @return Collection<int, ProductAisle> */
    public function getAisleAssignments(): Collection
    {
        return $this->aisleAssignments;
    }

    /**
     * Its aisle in each store, as bare ids: `[{store, aisle}]`.
     *
     * @return list<array{store: string, aisle: string}>
     */
    public function getAisles(): array
    {
        $aisles = [];
        foreach ($this->aisleAssignments as $assignment) {
            $aisles[] = [
                'store' => (string) $assignment->getStore()->getId(),
                'aisle' => (string) $assignment->getAisle()->getId(),
            ];
        }

        return $aisles;
    }

    /**
     * Only remembers what was sent; {@see \Maggie\Grocery\MessageHandler\UpdateProductHandler} applies it.
     * Each entry is `{store, aisle}`, ids or IRIs, `aisle` null for « Sans rayon ».
     *
     * @param array<int, array<string, string|null>> $aisles
     */
    public function setAisles(array $aisles): static
    {
        $input = [];
        foreach ($aisles as $entry) {
            $input[] = [
                'store' => (string) ($entry['store'] ?? ''),
                'aisle' => isset($entry['aisle']) && '' !== $entry['aisle'] ? (string) $entry['aisle'] : null,
            ];
        }
        $this->aislesInput = $input;

        return $this;
    }

    public function getSuggestionSnoozedUntil(): ?\DateTimeImmutable
    {
        return $this->suggestionSnoozedUntil;
    }

    public function setSuggestionSnoozedUntil(?\DateTimeImmutable $suggestionSnoozedUntil): static
    {
        $this->suggestionSnoozedUntil = $suggestionSnoozedUntil;

        return $this;
    }

    /** @return list<array{store: string, aisle: ?string}>|null */
    public function getAislesInput(): ?array
    {
        return $this->aislesInput;
    }

    public function aisleIn(Store $store): ?Aisle
    {
        return $this->assignmentIn($store)?->getAisle();
    }

    /** Puts the product in this aisle of the aisle's store, in place of the one it had there. */
    public function assignAisle(Aisle $aisle): static
    {
        $assignment = $this->assignmentIn($aisle->getStore());
        if (null === $assignment) {
            $this->aisleAssignments->add(new ProductAisle($this, $aisle));
        } elseif ($assignment->getAisle() !== $aisle) {
            $assignment->setAisle($aisle);
        }

        return $this;
    }

    /** « Sans rayon » in this store. */
    public function detachAisleIn(Store $store): static
    {
        $assignment = $this->assignmentIn($store);
        if (null !== $assignment) {
            $this->aisleAssignments->removeElement($assignment);
        }

        return $this;
    }

    private function assignmentIn(Store $store): ?ProductAisle
    {
        foreach ($this->aisleAssignments as $assignment) {
            if ((string) $assignment->getStore()->getId() === (string) $store->getId()) {
                return $assignment;
            }
        }

        return null;
    }

    /** @return list<int>|null */
    public function getSeasonMonths(): ?array
    {
        return $this->seasonMonths;
    }

    /**
     * Kept as given: the handlers call assertSeasonMonthsAreValid(), which
     * sorts it and refuses a month outside 1–12. An empty list is no season.
     *
     * @param list<int>|null $seasonMonths
     */
    public function setSeasonMonths(?array $seasonMonths): static
    {
        $this->seasonMonths = [] === $seasonMonths ? null : $seasonMonths;
        $this->seasonMonth = null;

        return $this;
    }

    /**
     * Called by the handlers once every field is applied, like assertPackagingIsConsistent().
     *
     * @throws InvalidSeasonMonthsException
     */
    public function assertSeasonMonthsAreValid(): void
    {
        if (null === $this->seasonMonths) {
            return;
        }

        // Typed list<int>, but a payload or a tool may send anything: checked here.
        /** @var list<mixed> $given */
        $given = $this->seasonMonths;
        foreach ($given as $month) {
            if (!\is_int($month) || $month < 1 || $month > 12) {
                throw new InvalidSeasonMonthsException(sprintf('seasonMonths takes months from 1 (January) to 12 (December), not %s.', json_encode($month)));
            }
        }

        $months = array_values(array_unique($this->seasonMonths));
        sort($months);
        $this->seasonMonths = $months;
    }

    /** Whether the product is in season that month; null for a product without a season. */
    public function isInSeasonIn(int $month): ?bool
    {
        return null === $this->seasonMonths ? null : \in_array($month, $this->seasonMonths, true);
    }

    /** Fills inSeason for that month — the month of the user's timezone, {@see SeasonCalendar}. */
    public function markSeason(int $month): static
    {
        $this->seasonMonth = $month;
        $this->inSeason = $this->isInSeasonIn($month);

        return $this;
    }

    /**
     * For the month marked by the read; unmarked (a Mercure payload, built
     * from the entity alone), for the month of today in Paris — the timezone
     * the calendar is written for.
     */
    public function getInSeason(): ?bool
    {
        if (null !== $this->seasonMonth) {
            return $this->inSeason;
        }

        return $this->isInSeasonIn(SeasonCalendar::monthInParis());
    }

    /**
     * Called by the handlers next to assertPackagingIsConsistent().
     *
     * @throws InvalidStockException
     */
    public function assertRestockQuantityIsValid(): void
    {
        if (null !== $this->restockQuantity && $this->restockQuantity < 0) {
            throw new InvalidStockException('restockQuantity must not be negative.');
        }
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
            'ciqualAlimCode' => $this->ciqualAlimCode,
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
            'stockState' => $this->stockState->value,
            'restockQuantity' => $this->restockQuantity,
            'autoRestock' => $this->autoRestock,
            'seasonMonths' => $this->seasonMonths,
            'suggestionSnoozedUntil' => $this->suggestionSnoozedUntil?->format('Y-m-d'),
        ];
    }

    public function toMercurePayload(?array $changedProperties = null): array
    {
        // inSeason follows seasonMonths: a differential update of the months carries both.
        if (null !== $changedProperties && \in_array('seasonMonths', $changedProperties, true)) {
            $changedProperties[] = 'inSeason';
        }

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
            'stockState' => $this->stockState->value,
            'restockQuantity' => $this->restockQuantity,
            'autoRestock' => $this->autoRestock,
            'aisles' => $this->getAisles(),
            'seasonMonths' => $this->seasonMonths,
            'inSeason' => $this->getInSeason(),
            'suggestionSnoozedUntil' => $this->suggestionSnoozedUntil?->format('Y-m-d'),
        ], $changedProperties, ['preferredStore' => 'preferredStoreId', 'fallbackStore' => 'fallbackStoreId', 'aisleAssignments' => 'aisles']);
    }
}
