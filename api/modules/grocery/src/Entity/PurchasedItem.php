<?php

declare(strict_types=1);

namespace Maggie\Grocery\Entity;

use Doctrine\ORM\Mapping as ORM;
use Maggie\Core\Contract\AggregateRoot;
use Maggie\Grocery\Enum\Unit;
use Symfony\Component\Serializer\Attribute\Ignore;
use Symfony\Component\Serializer\Attribute\SerializedName;
use Symfony\Component\Uid\Ulid;

/**
 * One line bought during an errand, as it stood on the list when the errand
 * ended: the product, the quantity and unit bought, and the product's
 * packaging at that moment when it had one (the « Stock et courses au plus
 * juste » contract: `packagingUnit`, `packagingSize`, `packagingSizeUnit`).
 */
#[ORM\Entity]
#[AggregateRoot('errand')]
class PurchasedItem
{
    #[ORM\Id]
    #[ORM\Column(type: 'ulid')]
    private Ulid $id;

    #[ORM\ManyToOne(targetEntity: Errand::class, inversedBy: 'items')]
    #[ORM\JoinColumn(nullable: false, onDelete: 'CASCADE')]
    #[Ignore]
    private Errand $errand;

    #[ORM\ManyToOne(targetEntity: Product::class)]
    #[ORM\JoinColumn(nullable: true, onDelete: 'SET NULL')]
    // Served as its IRI (getProductIri): the line keeps its own label and packaging.
    #[Ignore]
    private ?Product $product = null;

    #[ORM\Column(length: 255)]
    private string $label;

    #[ORM\Column(type: 'float', nullable: true)]
    private ?float $quantity = null;

    #[ORM\Column(length: 20, nullable: true, enumType: Unit::class)]
    private ?Unit $unit = null;

    #[ORM\Column(length: 20, nullable: true, enumType: Unit::class)]
    private ?Unit $packagingUnit = null;

    #[ORM\Column(type: 'float', nullable: true)]
    private ?float $packagingSize = null;

    #[ORM\Column(length: 20, nullable: true, enumType: Unit::class)]
    private ?Unit $packagingSizeUnit = null;

    #[ORM\Column(options: ['default' => 0])]
    private int $position = 0;

    public function __construct()
    {
        $this->id = new Ulid();
    }

    /** The line as it was bought: label, quantity, unit and the product's packaging. */
    public static function fromGroceryItem(GroceryItem $item): self
    {
        return self::of($item->getLabel(), $item->getQuantity(), $item->getUnit(), $item->getProduct());
    }

    /** A bought line; the packaging is the product's at this moment, when it has one. */
    public static function of(string $label, ?float $quantity, ?Unit $unit, ?Product $product = null): self
    {
        $purchased = new self();
        $purchased->label = $label;
        $purchased->quantity = $quantity;
        $purchased->unit = $unit;

        if (null !== $product) {
            $purchased->product = $product;
            $purchased->packagingUnit = $product->getPackagingUnit();
            $purchased->packagingSize = $product->getPackagingSize();
            $purchased->packagingSizeUnit = $product->getPackagingSizeUnit();
        }

        return $purchased;
    }

    public function getId(): Ulid
    {
        return $this->id;
    }

    public function getErrand(): Errand
    {
        return $this->errand;
    }

    public function setErrand(Errand $errand): static
    {
        $this->errand = $errand;

        return $this;
    }

    public function getProduct(): ?Product
    {
        return $this->product;
    }

    /** The product's IRI, as REST, Mercure and the MCP tool all spell it; null for a free-text line. */
    #[SerializedName('product')]
    public function getProductIri(): ?string
    {
        return null !== $this->product ? '/api/products/'.$this->product->getId() : null;
    }

    public function getLabel(): string
    {
        return $this->label;
    }

    public function getQuantity(): ?float
    {
        return $this->quantity;
    }

    public function getUnit(): ?Unit
    {
        return $this->unit;
    }

    public function getPackagingUnit(): ?Unit
    {
        return $this->packagingUnit;
    }

    public function getPackagingSize(): ?float
    {
        return $this->packagingSize;
    }

    public function getPackagingSizeUnit(): ?Unit
    {
        return $this->packagingSizeUnit;
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

    /**
     * The line as the Mercure update and the MCP tool spell it — the same
     * names as the REST resource.
     *
     * @return array<string, mixed>
     */
    public function toPayload(): array
    {
        return [
            'id' => (string) $this->id,
            'product' => $this->getProductIri(),
            'label' => $this->label,
            'quantity' => $this->quantity,
            'unit' => $this->unit?->value,
            'packagingUnit' => $this->packagingUnit?->value,
            'packagingSize' => $this->packagingSize,
            'packagingSizeUnit' => $this->packagingSizeUnit?->value,
        ];
    }
}
