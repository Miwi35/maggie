<?php

declare(strict_types=1);

namespace Maggie\Cookbook\Message;

final readonly class UpdateRecipeCommand
{
    /**
     * @param string[]|null $tags
     * @param array<array{quantity: float, unit: string, ingredientId?: string, ciqualFoodId?: string}>|null $ingredients
     */
    public function __construct(
        public string $recipeId,
        public ?string $name = null,
        public ?int $servings = null,
        public ?array $tags = null,
        public ?string $notes = null,
        public ?array $ingredients = null,
    ) {
    }
}
