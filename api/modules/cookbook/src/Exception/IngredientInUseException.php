<?php

declare(strict_types=1);

namespace Maggie\Cookbook\Exception;

/** An ingredient a recipe still lists cannot be deleted. The API answers it with a 409. */
final class IngredientInUseException extends \DomainException
{
    /** @param list<string> $recipeNames */
    public function __construct(string $ingredientName, array $recipeNames)
    {
        parent::__construct(sprintf(
            'Ingredient "%s" cannot be deleted: it is used in %s. Remove it from the recipe first.',
            $ingredientName,
            implode(', ', array_map(static fn (string $name) => '"'.$name.'"', $recipeNames)),
        ));
    }
}
