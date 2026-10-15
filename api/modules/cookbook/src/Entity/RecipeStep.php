<?php

declare(strict_types=1);

namespace Maggie\Cookbook\Entity;

use Doctrine\Common\Collections\ArrayCollection;
use Doctrine\Common\Collections\Collection;
use Doctrine\DBAL\Types\Types;
use Doctrine\ORM\Mapping as ORM;
use Maggie\Core\Contract\AggregateRoot;
use Symfony\Component\Serializer\Attribute\Context;
use Symfony\Component\Serializer\Attribute\Ignore;
use Symfony\Component\Serializer\Normalizer\AbstractObjectNormalizer;
use Symfony\Component\Uid\Ulid;

/**
 * One step of a recipe (MAG-490): its text, the recipe's ingredients and
 * utensils it uses, a duration — run in the background or not — and the
 * earlier steps it waits for.
 *
 * A step points at the {@see Ingredient}, never at the recipe's line: the
 * lines are recreated, with new ids, each time the recipe is written.
 *
 * Read as `{id, position, text, durationMinutes, background, ingredients:
 * [{id, name}], utensils: [{id, name}], waitsFor: [positions]}`: the Doctrine
 * links have names of their own, so the API reads the getters instead.
 */
#[ORM\Entity]
#[ORM\UniqueConstraint(name: 'recipe_step_position', columns: ['recipe_id', 'position'])]
#[AggregateRoot('recipe')]
class RecipeStep
{
    #[ORM\Id]
    #[ORM\Column(type: 'ulid')]
    private Ulid $id;

    #[ORM\ManyToOne(targetEntity: Recipe::class, inversedBy: 'steps')]
    #[ORM\JoinColumn(nullable: false, onDelete: 'CASCADE')]
    #[Ignore]
    private Recipe $recipe;

    /** 1 for the first step. */
    #[ORM\Column]
    private int $position;

    #[ORM\Column(type: Types::TEXT)]
    private string $text;

    // Served as null, not left out: a step without a duration says so.
    #[ORM\Column(nullable: true)]
    #[Context(normalizationContext: [AbstractObjectNormalizer::SKIP_NULL_VALUES => false])]
    private ?int $durationMinutes = null;

    /** The timer runs while the cook moves on to the next step. */
    #[ORM\Column]
    private bool $background = false;

    /** @var Collection<int, Ingredient> */
    #[ORM\ManyToMany(targetEntity: Ingredient::class)]
    #[ORM\JoinTable(name: 'recipe_step_ingredient')]
    #[ORM\JoinColumn(name: 'recipe_step_id', onDelete: 'CASCADE')]
    #[ORM\InverseJoinColumn(name: 'ingredient_id', onDelete: 'CASCADE')]
    private Collection $ingredientLinks;

    /** @var Collection<int, Utensil> */
    #[ORM\ManyToMany(targetEntity: Utensil::class)]
    #[ORM\JoinTable(name: 'recipe_step_utensil')]
    #[ORM\JoinColumn(name: 'recipe_step_id', onDelete: 'CASCADE')]
    #[ORM\InverseJoinColumn(name: 'utensil_id', onDelete: 'CASCADE')]
    private Collection $utensilLinks;

    /** @var Collection<int, RecipeStep> earlier steps of the same recipe */
    #[ORM\ManyToMany(targetEntity: RecipeStep::class)]
    #[ORM\JoinTable(name: 'recipe_step_wait')]
    #[ORM\JoinColumn(name: 'recipe_step_id', onDelete: 'CASCADE')]
    #[ORM\InverseJoinColumn(name: 'waits_for_id', onDelete: 'CASCADE')]
    private Collection $waitsForSteps;

    public function __construct()
    {
        $this->id = new Ulid();
        $this->ingredientLinks = new ArrayCollection();
        $this->utensilLinks = new ArrayCollection();
        $this->waitsForSteps = new ArrayCollection();
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
        $recipe->addStep($this);

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

    public function getText(): string
    {
        return $this->text;
    }

    public function setText(string $text): static
    {
        $this->text = $text;

        return $this;
    }

    public function getDurationMinutes(): ?int
    {
        return $this->durationMinutes;
    }

    public function setDurationMinutes(?int $durationMinutes): static
    {
        $this->durationMinutes = $durationMinutes;

        return $this;
    }

    public function isBackground(): bool
    {
        return $this->background;
    }

    public function setBackground(bool $background): static
    {
        $this->background = $background;

        return $this;
    }

    /** @return list<Ingredient> by name */
    public function linkedIngredients(): array
    {
        $ingredients = $this->ingredientLinks->getValues();
        usort($ingredients, static fn (Ingredient $a, Ingredient $b) => strcasecmp($a->getName(), $b->getName()));

        return $ingredients;
    }

    /** @return list<array{id: string, name: string}> by name */
    public function getIngredients(): array
    {
        return array_map(static fn (Ingredient $ingredient) => [
            'id' => (string) $ingredient->getId(),
            'name' => $ingredient->getName(),
        ], $this->linkedIngredients());
    }

    /** @param iterable<Ingredient> $ingredients the whole list */
    public function setIngredients(iterable $ingredients): static
    {
        $this->ingredientLinks->clear();
        foreach ($ingredients as $ingredient) {
            if (!$this->ingredientLinks->contains($ingredient)) {
                $this->ingredientLinks->add($ingredient);
            }
        }

        return $this;
    }

    /** @return list<Utensil> by name */
    public function linkedUtensils(): array
    {
        $utensils = $this->utensilLinks->getValues();
        usort($utensils, static fn (Utensil $a, Utensil $b) => strcasecmp($a->getName(), $b->getName()));

        return $utensils;
    }

    /** @return list<array{id: string, name: string}> by name */
    public function getUtensils(): array
    {
        return array_map(static fn (Utensil $utensil) => [
            'id' => (string) $utensil->getId(),
            'name' => $utensil->getName(),
        ], $this->linkedUtensils());
    }

    /** @param iterable<Utensil> $utensils the whole list */
    public function setUtensils(iterable $utensils): static
    {
        $this->utensilLinks->clear();
        foreach ($utensils as $utensil) {
            if (!$this->utensilLinks->contains($utensil)) {
                $this->utensilLinks->add($utensil);
            }
        }

        return $this;
    }

    public function removeUtensil(Utensil $utensil): static
    {
        $this->utensilLinks->removeElement($utensil);

        return $this;
    }

    /** @return list<int> the positions of the steps this one waits for, in order */
    public function getWaitsFor(): array
    {
        $positions = array_map(static fn (RecipeStep $step) => $step->getPosition(), $this->waitsForSteps->getValues());
        sort($positions);

        return $positions;
    }

    /** @param iterable<RecipeStep> $steps the whole list, earlier steps of the same recipe */
    public function setWaitsFor(iterable $steps): static
    {
        $this->waitsForSteps->clear();
        foreach ($steps as $step) {
            if (!$this->waitsForSteps->contains($step)) {
                $this->waitsForSteps->add($step);
            }
        }

        return $this;
    }

    /**
     * The step as the API serves it.
     *
     * @return array{id: string, position: int, text: string, durationMinutes: int|null, background: bool, ingredients: list<array{id: string, name: string}>, utensils: list<array{id: string, name: string}>, waitsFor: list<int>}
     */
    public function toPayload(): array
    {
        return [
            'id' => (string) $this->id,
            'position' => $this->position,
            'text' => $this->text,
            'durationMinutes' => $this->durationMinutes,
            'background' => $this->background,
            'ingredients' => $this->getIngredients(),
            'utensils' => $this->getUtensils(),
            'waitsFor' => $this->getWaitsFor(),
        ];
    }
}
