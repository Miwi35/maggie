<?php

declare(strict_types=1);

namespace Maggie\Cookbook\Mcp\Tool;

use Maggie\Cookbook\Entity\Recipe;
use Maggie\Cookbook\Message\CreateRecipeCommand;
use Maggie\Core\Mcp\McpUserContext;
use Maggie\Core\Mcp\MissingMcpUserException;
use Mcp\Capability\Attribute\McpTool;
use Symfony\Component\Messenger\Exception\HandlerFailedException;
use Symfony\Component\Messenger\MessageBusInterface;
use Symfony\Component\Messenger\Stamp\HandledStamp;

#[McpTool(name: 'create_recipe', description: 'Create a new recipe. Ingredients is a JSON array of objects with quantity, unit, and either ingredientId or ciqualAlimCode (to auto-create an Ingredient from Ciqual). Tags is a comma-separated list.')]
class CreateRecipeTool
{
    public function __construct(
        private readonly MessageBusInterface $bus,
        private readonly McpUserContext $userContext,
    ) {
    }

    public function __invoke(
        string $name,
        int $servings = 4,
        string $tags = '',
        ?string $notes = null,
        ?string $ingredients = null,
    ): string {
        try {
            $user = $this->userContext->requireUser();

            $tagList = $tags !== '' ? array_map('trim', explode(',', $tags)) : [];
            $ingredientList = $ingredients !== null
                ? json_decode($ingredients, true, 512, JSON_THROW_ON_ERROR)
                : null;

            $envelope = $this->bus->dispatch(new CreateRecipeCommand(
                userId: (string) $user->getId(),
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
        } catch (MissingMcpUserException $e) {
            return json_encode(['error' => $e->getMessage()], JSON_THROW_ON_ERROR);
        } catch (HandlerFailedException $e) {
            $cause = $e->getPrevious() ?? $e;
            return json_encode(['error' => $cause->getMessage()], JSON_THROW_ON_ERROR);
        }
    }
}
