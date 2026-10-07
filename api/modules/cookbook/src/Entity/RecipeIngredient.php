<?php

declare(strict_types=1);

namespace Maggie\Cookbook\Entity;

use Doctrine\ORM\Mapping as ORM;
use Maggie\Grocery\Enum\Unit;
use Symfony\Component\Uid\Ulid;
use Symfony\Component\Validator\Constraints as Assert;

#[ORM\Entity]
class RecipeIngredient
{
    #[ORM\Id]
    #[ORM\Column(type: 'ulid')]
    private Ulid $id;

    #[ORM\ManyToOne(targetEntity: Recipe::class, inversedBy: 'ingredients')]
    #[ORM\JoinColumn(nullable: false, onDelete: 'CASCADE')]
    private Recipe $recipe;

    #[ORM\ManyToOne(targetEntity: Ingredient::class)]
    #[ORM\JoinColumn(nullable: false)]
    #[Assert\NotNull]
    private Ingredient $ingredient;

    #[ORM\Column(type: 'float')]
    #[Assert\Positive]
    private float $quantity;

    #[ORM\Column(length: 20, enumType: Unit::class)]
    #[Assert\NotNull]
    private Unit $unit;

    public function __construct()
    {
        $this->id = new Ulid();
    }

    public function getId(): Ulid
    {
        return $this->id;
    }

    public function getRecipe(): Recipe
    {
        return $this->recipe;
    }

    public function setRecipe(Recipe $recipe): static
    {
        $this->recipe = $recipe;

        return $this;
    }

    public function getIngredient(): Ingredient
    {
        return $this->ingredient;
    }

    public function setIngredient(Ingredient $ingredient): static
    {
        $this->ingredient = $ingredient;

        return $this;
    }

    public function getQuantity(): float
    {
        return $this->quantity;
    }

    public function setQuantity(float $quantity): static
    {
        $this->quantity = $quantity;

        return $this;
    }

    public function getIngredientName(): string
    {
        return $this->ingredient->getName();
    }

    public function getCiqualAlimCode(): ?string
    {
        return $this->ingredient->getCiqualAlimCode();
    }

    /**
     * The line as the API serves it, so a client can swap it in and send it back.
     *
     * @return array<string, mixed>
     */
    public function toMercurePayload(): array
    {
        $ingredient = (string) $this->ingredient->getId();

        return [
            'id' => (string) $this->id,
            'ingredient' => [
                '@id' => '/api/ingredients/'.$ingredient,
                'id' => $ingredient,
                'name' => $this->ingredient->getName(),
                'ciqualAlimCode' => $this->ingredient->getCiqualAlimCode(),
            ],
            'ingredientName' => $this->ingredient->getName(),
            'ciqualAlimCode' => $this->ingredient->getCiqualAlimCode(),
            'quantity' => $this->quantity,
            'unit' => $this->unit->value,
        ];
    }

    public function getUnit(): Unit
    {
        return $this->unit;
    }

    public function setUnit(Unit $unit): static
    {
        $this->unit = $unit;

        return $this;
    }
}
