<?php

declare(strict_types=1);

namespace Maggie\Cookbook\Mcp\Tool;

use Maggie\Cookbook\Entity\Recipe;
use Maggie\Cookbook\Mcp\StepsArgument;
use Maggie\Cookbook\Mcp\UtensilsArgument;
use Maggie\Cookbook\Message\CreateRecipeCommand;
use Maggie\Core\Mcp\McpUserContext;
use Maggie\Core\Mcp\MissingMcpUserException;
use Mcp\Capability\Attribute\McpTool;
use Symfony\Component\HttpKernel\Exception\BadRequestHttpException;
use Symfony\Component\Messenger\Exception\HandlerFailedException;
use Symfony\Component\Messenger\MessageBusInterface;
use Symfony\Component\Messenger\Stamp\HandledStamp;

#[McpTool(name: 'create_recipe', description: 'A recipe is how to cook a dish; planning it on a day is a meal (manage_meals). Before: search_recipes, an existing recipe is updated, not created again. Create a recipe; with no ingredients and no steps it is refused, the error saying what is missing. ingredients: JSON array of {quantity, unit, ingredientId or ciqualAlimCode (creates the Ingredient from that Ciqual food), pieceWeightGrams?}; nutrition counts only ingredients with Ciqual values, and piece lines with pieceWeightGrams. steps: JSON array in order of {text, durationMinutes?, background? (timer runs while the cook moves on), ingredientIds? (recipe ids or names), utensils?, waitsFor? (earlier step positions)}. utensils: JSON array of ids or names; unknown names are added. tags: comma-separated.')]
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
        ?string $utensils = null,
        ?string $steps = null,
    ): string {
        try {
            $user = $this->userContext->requireUser();

            $tagList = '' !== $tags ? array_map('trim', explode(',', $tags)) : [];
            $ingredientList = null !== $ingredients
                ? json_decode($ingredients, true, 512, JSON_THROW_ON_ERROR)
                : null;
            $utensilList = UtensilsArgument::parse($utensils);
            $stepList = StepsArgument::parse($steps);

            if ([] === ($ingredientList ?? []) && [] === ($stepList ?? [])) {
                throw new BadRequestHttpException(sprintf('Recipe "%s" has no ingredients and no steps: nothing was created. A recipe needs its ingredients (ingredients) or its method (steps), or both. Tell the user what is missing and read it from the source or ask for it; do not retry with the title alone.', $name));
            }

            $envelope = $this->bus->dispatch(new CreateRecipeCommand(
                userId: (string) $user->getId(),
                name: $name,
                servings: $servings,
                tags: $tagList,
                notes: $notes,
                ingredients: $ingredientList,
                utensils: $utensilList,
                steps: $stepList,
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
                    'steps' => $recipe->stepsPayload(),
                ],
            ], JSON_THROW_ON_ERROR);
        } catch (MissingMcpUserException|BadRequestHttpException $e) {
            return json_encode(['error' => $e->getMessage()], JSON_THROW_ON_ERROR);
        } catch (HandlerFailedException $e) {
            $cause = $e->getPrevious() ?? $e;

            return json_encode(['error' => $cause->getMessage()], JSON_THROW_ON_ERROR);
        }
    }
}
