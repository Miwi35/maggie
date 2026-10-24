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

#[McpTool(name: 'create_recipe', description: 'Create a new recipe. A recipe is its ingredients and its steps: with neither, nothing is created and the error says what is missing — never create an empty recipe from a title alone (a screenshot of a page shows the title and the photo, not the recipe). When the user has not given them and you cannot read them from the source (the page, a screenshot, a quoted message), tell the user so and offer to read the page or ask for the ingredients and the method. Ingredients is a JSON array of objects with quantity, unit, and either ingredientId or ciqualAlimCode (to auto-create an Ingredient from Ciqual). Tags is a comma-separated list. Utensils is a JSON array of what the recipe is made with, each a utensil id (from manage_utensils) or a name — a name not in the catalogue yet is added to it. '.StepsArgument::DESCRIPTION)]
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
