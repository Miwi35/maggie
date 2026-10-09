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
class GroceryItem
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
