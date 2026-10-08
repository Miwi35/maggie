<?php

declare(strict_types=1);

namespace Maggie\Cookbook\Controller;

use Maggie\Cookbook\Entity\Meal;
use Maggie\Cookbook\Message\ChooseMealGroceriesCommand;
use Maggie\Cookbook\Repository\MealRepository;
use Maggie\Cookbook\Service\MealGroceryChoice;
use Maggie\Core\Entity\User;
use Symfony\Bundle\SecurityBundle\Security;
use Symfony\Component\HttpFoundation\JsonResponse;
use Symfony\Component\HttpFoundation\Request;
use Symfony\Component\HttpFoundation\Response;
use Symfony\Component\Messenger\Exception\HandlerFailedException;
use Symfony\Component\Messenger\MessageBusInterface;
use Symfony\Component\Messenger\Stamp\HandledStamp;
use Symfony\Component\Routing\Attribute\Route;

/**
 * Puts the ingredients the owner chose on the grocery list, in packagings
 * (MAG-295).
 *
 * Body: `{"ingredients": [{"ingredientId": "…", "quantity": 2}]}`, `quantity`
 * optional — packagings to force instead of what the recipes ask. Answers the
 * meal's preview as it now stands, its choice stamped.
 */
final class MealGroceryItemsController
{
    public function __construct(
        private readonly Security $security,
        private readonly MealRepository $mealRepository,
        private readonly MealGroceryChoice $choice,
        private readonly MessageBusInterface $bus,
    ) {
    }

    #[Route('/api/meals/{id}/grocery_items', name: 'api_meal_grocery_items', methods: ['POST'])]
    public function __invoke(string $id, Request $request): JsonResponse
    {
        $user = $this->security->getUser();
        if (!$user instanceof User) {
            return new JsonResponse(['error' => 'Unauthorized'], Response::HTTP_UNAUTHORIZED);
        }

        $meal = $this->mealRepository->findOneForUser($id, $user);
        if (null === $meal) {
            return new JsonResponse(['error' => 'Meal not found'], Response::HTTP_NOT_FOUND);
        }

        $chosen = $this->chosenFrom($request);
        if (\is_string($chosen)) {
            return new JsonResponse(['error' => $chosen], Response::HTTP_BAD_REQUEST);
        }

        try {
            $envelope = $this->bus->dispatch(new ChooseMealGroceriesCommand(
                mealId: (string) $meal->getId(),
                userId: (string) $user->getId(),
                chosen: $chosen,
            ));
        } catch (HandlerFailedException $e) {
            $cause = $e->getPrevious() ?? $e;
            if (!$cause instanceof \DomainException) {
                throw $e;
            }

            return new JsonResponse(['error' => $cause->getMessage()], Response::HTTP_BAD_REQUEST);
        }

        /** @var Meal $meal */
        $meal = $envelope->last(HandledStamp::class)?->getResult();

        return new JsonResponse($this->choice->preview($meal));
    }

    /**
     * The chosen ingredients, or what is wrong with the body.
     *
     * @return array<string, float|null>|string
     */
    private function chosenFrom(Request $request): array|string
    {
        try {
            $body = json_decode($request->getContent(), true, 512, \JSON_THROW_ON_ERROR);
        } catch (\JsonException) {
            return 'The body must be JSON.';
        }

        if (!\is_array($body) || !isset($body['ingredients']) || !\is_array($body['ingredients']) || !array_is_list($body['ingredients'])) {
            return 'ingredients is required: a list of {ingredientId, quantity?}.';
        }

        $chosen = [];

        foreach ($body['ingredients'] as $entry) {
            if (!\is_array($entry) || !isset($entry['ingredientId']) || !\is_string($entry['ingredientId'])) {
                return 'Each ingredient needs an ingredientId.';
            }

            $quantity = $entry['quantity'] ?? null;
            if (null !== $quantity && (!\is_int($quantity) && !\is_float($quantity) || $quantity <= 0)) {
                return 'quantity must be a positive number.';
            }

            $chosen[$entry['ingredientId']] = null !== $quantity ? (float) $quantity : null;
        }

        return $chosen;
    }
}
