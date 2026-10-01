<?php

declare(strict_types=1);

namespace Maggie\Cookbook\Entity;

use Doctrine\ORM\Mapping as ORM;
use Maggie\Cookbook\Repository\MealGroceryContributionRepository;
use Maggie\Grocery\Entity\GroceryItem;
use Maggie\Grocery\Enum\Unit;
use Symfony\Component\Uid\Ulid;

/**
 * What one meal put on the grocery list, and how much of it.
 *
 * A grocery line is shared: two meals needing tomatoes add up into a single
 * "Tomate 5" the shopper reads once. Without this row nothing knows how much
 * of that 5 came from Monday's dinner, so moving or cancelling the dinner left
 * the ingredients on the list for ever (MAG-116). Revoking a contribution
 * subtracts its quantity and drops the line only when nothing else holds it.
 *
 * It lives in the cookbook module because the dependency runs cookbook →
 * grocery (Ingredient extends Product); the reverse would make a cycle.
 */
#[ORM\Entity(repositoryClass: MealGroceryContributionRepository::class)]
#[ORM\Table(name: 'meal_grocery_contribution')]
#[ORM\UniqueConstraint(name: 'uniq_meal_grocery_contribution', columns: ['meal_id', 'grocery_item_id'])]
class MealGroceryContribution
{
    #[ORM\Id]
    #[ORM\Column(type: 'ulid')]
    private Ulid $id;

    #[ORM\ManyToOne(targetEntity: Meal::class)]
    #[ORM\JoinColumn(nullable: false, onDelete: 'CASCADE')]
    private Meal $meal;

    #[ORM\ManyToOne(targetEntity: GroceryItem::class)]
    #[ORM\JoinColumn(nullable: false, onDelete: 'CASCADE')]
    private GroceryItem $groceryItem;

    #[ORM\Column(type: 'float')]
    private float $quantity = 0.0;

    #[ORM\Column(length: 20, nullable: true, enumType: Unit::class)]
    private ?Unit $unit = null;

    public function __construct()
    {
        $this->id = new Ulid();
    }

    public function getId(): Ulid
    {
        return $this->id;
    }

    public function getMeal(): Meal
    {
        return $this->meal;
    }

    public function setMeal(Meal $meal): static
    {
        $this->meal = $meal;

        return $this;
    }

    public function getGroceryItem(): GroceryItem
    {
        return $this->groceryItem;
    }

    public function setGroceryItem(GroceryItem $groceryItem): static
    {
        $this->groceryItem = $groceryItem;

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

    public function addQuantity(float $quantity): static
    {
        $this->quantity += $quantity;

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
}
