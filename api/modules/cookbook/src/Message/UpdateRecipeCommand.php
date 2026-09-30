<?php

declare(strict_types=1);

namespace Maggie\Cookbook\Message;

use Maggie\Core\Message\ClearsFieldsTrait;

final readonly class UpdateRecipeCommand
{
    use ClearsFieldsTrait;

    /**
     * @param string[]|null $tags
     * @param array<array{quantity: float, unit: string, ingredientId?: string, ciqualAlimCode?: string}>|null $ingredients
     * @param list<'notes'> $clearFields
     */
    public function __construct(
        public string $recipeId,
        public ?string $name = null,
        public ?int $servings = null,
        public ?array $tags = null,
        public ?string $notes = null,
        public ?array $ingredients = null,
        public array $clearFields = [],
    ) {
    }
}
