<?php

declare(strict_types=1);

namespace Maggie\Cookbook\Mcp\Tool;

use Maggie\Cookbook\Entity\Recipe;
use Maggie\Cookbook\Mcp\UtensilsArgument;
use Maggie\Cookbook\Message\UpdateRecipeCommand;
use Mcp\Capability\Attribute\McpTool;
use Symfony\Component\HttpKernel\Exception\BadRequestHttpException;
use Symfony\Component\Messenger\Exception\HandlerFailedException;
use Symfony\Component\Messenger\MessageBusInterface;
use Symfony\Component\Messenger\Stamp\HandledStamp;

#[McpTool(name: 'update_recipe', description: 'Update an existing recipe. Only provided fields will be updated. Ingredients replaces the full list if provided. Each ingredient object needs quantity, unit, and either ingredientId or ciqualAlimCode. To empty an optional field, list its name in clear (notes); an empty tags string empties the tags. Utensils is a JSON array of what the recipe is made with, each a utensil id (from manage_utensils) or a name — a name not in the catalogue yet is added to it; it replaces the full list if provided, and [] empties it.')]
class UpdateRecipeTool
{
    public function __construct(
        private readonly MessageBusInterface $bus,
    ) {
    }

    /** @param list<string>|null $clear */
    public function __invoke(
        string $recipeId,
        ?string $name = null,
        ?int $servings = null,
        ?string $tags = null,
        ?string $notes = null,
        ?string $ingredients = null,
        ?array $clear = null,
        ?string $utensils = null,
    ): string {
        try {
            $tagList = null !== $tags ? ('' === $tags ? [] : array_map('trim', explode(',', $tags))) : null;
            $ingredientList = null !== $ingredients
                ? json_decode($ingredients, true, 512, JSON_THROW_ON_ERROR)
                : null;
            $utensilList = UtensilsArgument::parse($utensils);

            $envelope = $this->bus->dispatch(new UpdateRecipeCommand(
                recipeId: $recipeId,
                name: $name,
                servings: $servings,
                tags: $tagList,
                notes: $notes,
                ingredients: $ingredientList,
                utensils: $utensilList,
                clearFields: array_values(array_intersect($clear ?? [], ['notes'])),
            ));

            /** @var Recipe $recipe */
            $recipe = $envelope->last(HandledStamp::class)->getResult();

            return json_encode([
                'success' => true,
                'recipe' => [
                    'id' => (string) $recipe->getId(),
                    'name' => $recipe->getName(),
                    'servings' => $recipe->getServings(),
                    'tags' => $recipe->getTags(),
                    'utensils' => UtensilsArgument::describe($recipe),
                ],
            ], JSON_THROW_ON_ERROR);
        } catch (BadRequestHttpException $e) {
            return json_encode(['error' => $e->getMessage()], JSON_THROW_ON_ERROR);
        } catch (HandlerFailedException $e) {
            $cause = $e->getPrevious() ?? $e;

            return json_encode(['error' => $cause->getMessage()], JSON_THROW_ON_ERROR);
        }
    }
}
