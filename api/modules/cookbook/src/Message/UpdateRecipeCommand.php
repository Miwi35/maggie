<?php

declare(strict_types=1);

namespace Maggie\Cookbook\Message;

use Maggie\Cookbook\State\RecipeStepsPayload;
use Maggie\Core\Message\ClearsFieldsTrait;

/** @phpstan-import-type StepInput from RecipeStepsPayload */
final readonly class UpdateRecipeCommand
{
    use ClearsFieldsTrait;

    /**
     * @param string[]|null                                                                                    $tags
     * @param array<array{quantity: float, unit: string, ingredientId?: string, ciqualAlimCode?: string}>|null $ingredients
     * @param list<array{id: string}|array{name: string}>|null                                                 $utensils    by id or by name, the whole list
     * @param list<StepInput>|null                                                                             $steps       the whole list, in order
     * @param list<'notes'>                                                                                    $clearFields
     */
    public function __construct(
        public string $recipeId,
        public ?string $name = null,
        public ?int $servings = null,
        public ?array $tags = null,
        public ?string $notes = null,
        public ?array $ingredients = null,
        public ?array $utensils = null,
        public ?array $steps = null,
        public array $clearFields = [],
    ) {
    }
}
