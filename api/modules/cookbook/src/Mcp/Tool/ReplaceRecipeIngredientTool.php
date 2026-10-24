<?php

declare(strict_types=1);

namespace Maggie\Cookbook\Mcp\Tool;

use Maggie\Cookbook\Entity\Recipe;
use Maggie\Cookbook\Exception\InvalidIngredientReplacementException;
use Maggie\Cookbook\Exception\InvalidRecipeStepsException;
use Maggie\Cookbook\Message\ReplaceRecipeIngredientCommand;
use Maggie\Cookbook\Repository\RecipeRepository;
use Maggie\Core\Mcp\McpUserContext;
use Maggie\Core\Mcp\MissingMcpUserException;
use Mcp\Capability\Attribute\McpTool;
use Symfony\Component\Messenger\Exception\HandlerFailedException;
use Symfony\Component\Messenger\MessageBusInterface;
use Symfony\Component\Messenger\Stamp\HandledStamp;
use Symfony\Component\Uid\Ulid;

#[McpTool(name: 'replace_recipe_ingredient', description: 'Give a line of a recipe another product, without breaking its steps: the steps that used the old ingredient use the new one. ingredientId is the ingredient the recipe line has now; give the new one as withIngredientId or withCiqualAlimCode; quantity and unit are optional (kept when omitted). The text of the steps is never rewritten on a deduction: the answer lists textSuggestions ({position, before, after}) for the steps that cite the old name or quantity — pass rewriteText true only when the user asked for the texts to follow.')]
class ReplaceRecipeIngredientTool
{
    public function __construct(
        private readonly MessageBusInterface $bus,
        private readonly RecipeRepository $recipeRepository,
        private readonly McpUserContext $userContext,
    ) {
    }

    public function __invoke(
        string $recipeId,
        string $ingredientId,
        ?string $withIngredientId = null,
        ?string $withCiqualAlimCode = null,
        ?float $quantity = null,
        ?string $unit = null,
        bool $rewriteText = false,
    ): string {
        try {
            $user = $this->userContext->requireUser();
            $recipe = Ulid::isValid($recipeId) ? $this->recipeRepository->findOneForUser($recipeId, $user) : null;
            if (null === $recipe) {
                return json_encode(['error' => "Recipe not found: {$recipeId}"], JSON_THROW_ON_ERROR);
            }
            if ((null === $withIngredientId) === (null === $withCiqualAlimCode)) {
                return json_encode(['error' => 'Give the new ingredient as withIngredientId or withCiqualAlimCode.'], JSON_THROW_ON_ERROR);
            }
            if (null !== $quantity && $quantity <= 0) {
                return json_encode(['error' => 'quantity must be a positive number.'], JSON_THROW_ON_ERROR);
            }

            $envelope = $this->bus->dispatch(new ReplaceRecipeIngredientCommand(
                recipeId: (string) $recipe->getId(),
                ingredientId: $ingredientId,
                withIngredientId: $withIngredientId,
                withCiqualAlimCode: $withCiqualAlimCode,
                quantity: $quantity,
                unit: $unit,
                rewriteText: $rewriteText,
            ));

            /** @var array{recipe: Recipe, suggestions: list<array<string, mixed>>} $result */
            $result = $envelope->last(HandledStamp::class)->getResult();

            return json_encode([
                'success' => true,
                'textRewritten' => $rewriteText,
                'textSuggestions' => $result['suggestions'],
                'recipe' => [
                    'id' => (string) $result['recipe']->getId(),
                    'name' => $result['recipe']->getName(),
                    'steps' => $result['recipe']->stepsPayload(),
                ],
            ], JSON_THROW_ON_ERROR);
        } catch (MissingMcpUserException $e) {
            return json_encode(['error' => $e->getMessage()], JSON_THROW_ON_ERROR);
        } catch (HandlerFailedException $e) {
            $cause = $e->getPrevious() ?? $e;
            if (!$cause instanceof InvalidIngredientReplacementException && !$cause instanceof InvalidRecipeStepsException) {
                throw $e;
            }

            return json_encode(['error' => $cause->getMessage()], JSON_THROW_ON_ERROR);
        }
    }
}
