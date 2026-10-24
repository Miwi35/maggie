<?php

declare(strict_types=1);

namespace Maggie\Cookbook\Message;

/**
 * Gives a recipe line another product (MAG-601). The line is named by the
 * product it has now; the new one by its id or by its Ciqual code. The links of
 * the steps follow; their text only when `rewriteText` says so.
 */
final readonly class ReplaceRecipeIngredientCommand
{
    public function __construct(
        public string $recipeId,
        public string $ingredientId,
        public ?string $withIngredientId = null,
        public ?string $withCiqualAlimCode = null,
        public ?float $quantity = null,
        public ?string $unit = null,
        public bool $rewriteText = false,
    ) {
    }
}
