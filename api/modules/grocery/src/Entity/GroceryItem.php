<?php

declare(strict_types=1);

namespace Maggie\Grocery\Entity;

use Doctrine\DBAL\Types\Types;
use Doctrine\ORM\Mapping as ORM;
use Maggie\Core\Contract\AggregateRoot;
use Maggie\Grocery\Enum\GroceryItemSource;
use Maggie\Grocery\Enum\Unit;
use Symfony\Component\Uid\Ulid;

#[ORM\Entity]
#[AggregateRoot('groceryList')]
class GroceryItem implements SeasonalLine
{
    #[ORM\Id]
    #[ORM\Column(type: 'ulid')]
    private Ulid $id;

    #[ORM\ManyToOne(targetEntity: GroceryList::class, inversedBy: 'items')]
    #[ORM\JoinColumn(nullable: false, onDelete: 'CASCADE')]
    private GroceryList $groceryList;

    #[ORM\ManyToOne(targetEntity: Product::class)]
    #[ORM\JoinColumn(nullable: true)]
    private ?Product $product = null;

    #[ORM\Column(length: 255, nullable: true)]
    private ?string $customLabel = null;

    #[ORM\Column(type: 'float', nullable: true)]
    private ?float $quantity = null;

    #[ORM\Column(length: 20, nullable: true, enumType: Unit::class)]
    private ?Unit $unit = null;

    /**
     * What the meals (and anything else counted in a quantity) need of the
     * product, in base units — « 800 g », or « 300 g + 2 pièces ». The line's
     * `quantity` is what to buy; this is why. Null until something sets it.
     *
     * @var list<array{quantity: float, unit: ?string}>|null
     */
    #[ORM\Column(type: 'json', nullable: true)]
    private ?array $needed = null;

    /**
     * What the owner wants of a packaged product, in base units (« 1,4 kg » is
     * 1400 g): `quantity` counts the packagings that cover it, rounded up, and
     * may exceed it. Null when nothing was asked in a quantity.
     */
    #[ORM\Column(type: 'float', nullable: true)]
    private ?float $desiredQuantity = null;

    #[ORM\Column(length: 20, nullable: true, enumType: Unit::class)]
    private ?Unit $desiredUnit = null;

    #[ORM\Column(options: ['default' => false])]
    private bool $checked = false;

    #[ORM\Column(length: 20, enumType: GroceryItemSource::class)]
    private GroceryItemSource $source;

    #[ORM\ManyToOne(targetEntity: Store::class)]
    #[ORM\JoinColumn(nullable: true, onDelete: 'SET NULL')]
    private ?Store $store = null;

    #[ORM\Column(type: Types::DATE_IMMUTABLE, nullable: true)]
    private ?\DateTimeImmutable $buyAfter = null;

    #[ORM\Column(options: ['default' => 0])]
    private int $position = 0;

    public function __construct()
    {
        $this->id = new Ulid();
    }

    public function getId(): Ulid
    {
        return $this->id;
    }

    public function getGroceryList(): GroceryList
    {
        return $this->groceryList;
    }

    public function setGroceryList(GroceryList $groceryList): static
    {
        $this->groceryList = $groceryList;

        return $this;
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

    /** The product's: null for a free-text line or a product without a season (MAG-494). */
    public function getInSeason(): ?bool
    {
        return $this->product?->getInSeason();
    }

    public function seasonalProduct(): ?Product
    {
        return $this->product;
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

    /** @return list<array{quantity: float, unit: ?string}>|null */
    public function getNeeded(): ?array
    {
        return $this->needed;
    }

    /** @param list<array{quantity: float, unit: ?string}>|null $needed */
    public function setNeeded(?array $needed): static
    {
        $this->needed = [] === $needed ? null : $needed;

        return $this;
    }

    public function getDesiredQuantity(): ?float
    {
        return $this->desiredQuantity;
    }

    public function getDesiredUnit(): ?Unit
    {
        return $this->desiredUnit;
    }

    /** The wanted quantity in base units, or none (both parts are set or cleared together). */
    public function applyDesired(?float $quantity, ?Unit $unit): static
    {
        $this->desiredQuantity = null === $quantity ? null : $quantity;
        $this->desiredUnit = null === $quantity ? null : $unit;

        return $this;
    }

    /**
     * Where the line stands on quantities, as an error body recalls it.
     *
     * @return array<string, mixed>
     */
    public function quantityState(): array
    {
        return [
            'id' => (string) $this->id,
            'quantity' => $this->quantity,
            'unit' => $this->unit?->value,
            'desiredQuantity' => $this->desiredQuantity,
            'desiredUnit' => $this->desiredUnit?->value,
            'needed' => $this->needed,
        ];
    }

    public function isChecked(): bool
    {
        return $this->checked;
    }

    public function setChecked(bool $checked): static
    {
        $this->checked = $checked;

        return $this;
    }

    public function getSource(): GroceryItemSource
    {
        return $this->source;
    }

    public function setSource(GroceryItemSource $source): static
    {
        $this->source = $source;

        return $this;
    }

    public function getStore(): ?Store
    {
        return $this->store;
    }

    public function setStore(?Store $store): static
    {
        $this->store = $store;

        return $this;
    }

    /**
     * The aisle of the line's product in the line's store, null when either is
     * missing or the product has no aisle there (« Sans rayon », MAG-493).
     *
     * @return array{id: string, name: string, position: int}|null
     */
    public function getAisle(): ?array
    {
        if (null === $this->store || null === $this->product) {
            return null;
        }

        return $this->product->aisleIn($this->store)?->toSummary();
    }

    public function getBuyAfter(): ?\DateTimeImmutable
    {
        return $this->buyAfter;
    }

    public function setBuyAfter(?\DateTimeImmutable $buyAfter): static
    {
        $this->buyAfter = $buyAfter;

        return $this;
    }

    public function getPosition(): int
    {
        return $this->position;
    }

    public function setPosition(int $position): static
    {
        $this->position = $position;

        return $this;
    }
}
