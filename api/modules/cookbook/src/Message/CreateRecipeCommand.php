<?php

declare(strict_types=1);

namespace Maggie\Cookbook\Message;

final readonly class CreateRecipeCommand
{
    /**
     * @param string[]                                                                                         $tags
     * @param array<array{quantity: float, unit: string, ingredientId?: string, ciqualAlimCode?: string}>|null $ingredients
     */
    public function __construct(
        public string $userId,
        public string $name,
        public int $servings = 4,
        public array $tags = [],
        public ?string $notes = null,
        public ?array $ingredients = null,
    ) {
    }
}
