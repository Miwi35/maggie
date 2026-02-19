<?php

declare(strict_types=1);

namespace Maggie\Cookbook\Mcp\Tool;

use Maggie\Cookbook\Entity\Recipe;
use Maggie\Cookbook\Message\UpdateRecipeCommand;
use Mcp\Capability\Attribute\McpTool;
use Symfony\Component\Messenger\Exception\HandlerFailedException;
use Symfony\Component\Messenger\MessageBusInterface;
use Symfony\Component\Messenger\Stamp\HandledStamp;

#[McpTool(name: 'update_recipe', description: 'Update an existing recipe. Only provided fields will be updated. Ingredients replaces the full list if provided.')]
class UpdateRecipeTool
{
    public function __construct(
        private readonly MessageBusInterface $bus,
    ) {
    }

    public function __invoke(
        string $recipeId,
        ?string $name = null,
        ?int $servings = null,
        ?string $tags = null,
        ?string $notes = null,
        ?string $ingredients = null,
    ): string {
        try {
            $tagList = $tags !== null ? array_map('trim', explode(',', $tags)) : null;
            $ingredientList = $ingredients !== null
                ? json_decode($ingredients, true, 512, JSON_THROW_ON_ERROR)
                : null;

            $envelope = $this->bus->dispatch(new UpdateRecipeCommand(
                recipeId: $recipeId,
                name: $name,
                servings: $servings,
                tags: $tagList,
                notes: $notes,
                ingredients: $ingredientList,
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
                ],
            ], JSON_THROW_ON_ERROR);
        } catch (HandlerFailedException $e) {
            $cause = $e->getPrevious() ?? $e;
            return json_encode(['error' => $cause->getMessage()], JSON_THROW_ON_ERROR);
        }
    }
}
