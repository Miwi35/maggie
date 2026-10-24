<?php

declare(strict_types=1);

namespace Maggie\Cookbook\Controller;

use Maggie\Cookbook\Entity\Recipe;
use Maggie\Cookbook\Exception\InvalidIngredientReplacementException;
use Maggie\Cookbook\Exception\InvalidRecipeStepsException;
use Maggie\Cookbook\Message\ReplaceRecipeIngredientCommand;
use Maggie\Cookbook\Repository\RecipeRepository;
use Maggie\Core\Entity\User;
use Symfony\Bundle\SecurityBundle\Security;
use Symfony\Component\HttpFoundation\JsonResponse;
use Symfony\Component\HttpFoundation\Request;
use Symfony\Component\HttpFoundation\Response;
use Symfony\Component\Messenger\Exception\HandlerFailedException;
use Symfony\Component\Messenger\MessageBusInterface;
use Symfony\Component\Messenger\Stamp\HandledStamp;
use Symfony\Component\Routing\Attribute\Route;
use Symfony\Component\Uid\Ulid;

/**
 * Gives a line of a recipe another product, and the steps' links follow (MAG-601).
 *
 * Body: `{"ingredientId": "<product the line has now>", "with": {"ingredientId": "…"}
 * | {"ciqualAlimCode": "…"}, "quantity"?: 2, "unit"?: "g", "rewriteText"?: false}`.
 * The text of the steps is rewritten only when `rewriteText` is true; otherwise the
 * answer's `textSuggestions` (`{stepId, position, before, after}`) say what would change.
 * Answers `{recipe, textSuggestions, textRewritten}`.
 *
 * @phpstan-import-type TextSuggestion from \Maggie\Cookbook\Service\RecipeIngredientReplacer
 */
final class ReplaceRecipeIngredientController
{
    public function __construct(
        private readonly Security $security,
        private readonly RecipeRepository $recipeRepository,
        private readonly MessageBusInterface $bus,
    ) {
    }

    #[Route('/api/recipes/{id}/replace-ingredient', name: 'api_recipe_replace_ingredient', methods: ['POST'])]
    public function __invoke(string $id, Request $request): JsonResponse
    {
        $user = $this->security->getUser();
        if (!$user instanceof User) {
            return new JsonResponse(['error' => 'Unauthorized'], Response::HTTP_UNAUTHORIZED);
        }

        $recipe = Ulid::isValid($id) ? $this->recipeRepository->findOneForUser($id, $user) : null;
        if (null === $recipe) {
            return new JsonResponse(['error' => 'Recipe not found'], Response::HTTP_NOT_FOUND);
        }

        $command = $this->commandFrom($recipe, $request);
        if (\is_string($command)) {
            return new JsonResponse(['error' => $command], Response::HTTP_BAD_REQUEST);
        }

        try {
            $envelope = $this->bus->dispatch($command);
        } catch (HandlerFailedException $e) {
            $cause = $e->getPrevious() ?? $e;
            if (!$cause instanceof InvalidIngredientReplacementException && !$cause instanceof InvalidRecipeStepsException) {
                throw $e;
            }

            return new JsonResponse(['error' => $cause->getMessage()], Response::HTTP_BAD_REQUEST);
        }

        /** @var array{recipe: Recipe, suggestions: list<TextSuggestion>} $result */
        $result = $envelope->last(HandledStamp::class)?->getResult();
        $updated = $result['recipe'];

        return new JsonResponse([
            'recipe' => ['@id' => '/api/recipes/'.$updated->getId(), 'id' => (string) $updated->getId()] + $updated->toMercurePayload(),
            'textSuggestions' => $result['suggestions'],
            'textRewritten' => $command->rewriteText,
        ]);
    }

    private function commandFrom(Recipe $recipe, Request $request): ReplaceRecipeIngredientCommand|string
    {
        try {
            $body = json_decode($request->getContent(), true, 512, \JSON_THROW_ON_ERROR);
        } catch (\JsonException) {
            return 'The body must be JSON.';
        }
        if (!\is_array($body) || !isset($body['ingredientId']) || !\is_string($body['ingredientId']) || '' === $body['ingredientId']) {
            return 'ingredientId is required: the ingredient the line has now.';
        }
        $with = $body['with'] ?? null;
        $withId = \is_array($with) ? ($with['ingredientId'] ?? null) : null;
        $withCode = \is_array($with) ? ($with['ciqualAlimCode'] ?? null) : null;
        if ((!\is_string($withId) || '' === $withId) === (!\is_string($withCode) || '' === $withCode)) {
            return 'with is required: {ingredientId} or {ciqualAlimCode}.';
        }

        $quantity = $body['quantity'] ?? null;
        if (null !== $quantity && (!\is_int($quantity) && !\is_float($quantity) || $quantity <= 0)) {
            return 'quantity must be a positive number.';
        }
        $unit = $body['unit'] ?? null;
        if (null !== $unit && !\is_string($unit)) {
            return 'unit must be a string.';
        }
        $rewriteText = $body['rewriteText'] ?? false;
        if (!\is_bool($rewriteText)) {
            return 'rewriteText must be true or false.';
        }

        return new ReplaceRecipeIngredientCommand(
            recipeId: (string) $recipe->getId(),
            ingredientId: $body['ingredientId'],
            withIngredientId: \is_string($withId) && '' !== $withId ? $withId : null,
            withCiqualAlimCode: \is_string($withCode) && '' !== $withCode ? $withCode : null,
            quantity: null !== $quantity ? (float) $quantity : null,
            unit: $unit,
            rewriteText: $rewriteText,
        );
    }
}
