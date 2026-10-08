<?php

declare(strict_types=1);

namespace Maggie\Cookbook\Controller;

use Maggie\Cookbook\Repository\MealRepository;
use Maggie\Cookbook\Service\MealGroceryChoice;
use Maggie\Core\Entity\User;
use Symfony\Bundle\SecurityBundle\Security;
use Symfony\Component\HttpFoundation\JsonResponse;
use Symfony\Component\HttpFoundation\Response;
use Symfony\Component\Routing\Attribute\Route;

/**
 * What a meal could put on the grocery list, in packagings and with each
 * product's stock, for the owner to choose from (MAG-295).
 *
 * A dedicated controller and not an API Platform operation: it is a reading
 * derived from the meal's recipes, its products and their packaging, not the
 * representation of a resource.
 */
final class MealGroceryPreviewController
{
    public function __construct(
        private readonly Security $security,
        private readonly MealRepository $mealRepository,
        private readonly MealGroceryChoice $choice,
    ) {
    }

    #[Route('/api/meals/{id}/grocery_preview', name: 'api_meal_grocery_preview', methods: ['GET'])]
    public function __invoke(string $id): JsonResponse
    {
        $user = $this->security->getUser();
        if (!$user instanceof User) {
            return new JsonResponse(['error' => 'Unauthorized'], Response::HTTP_UNAUTHORIZED);
        }

        $meal = $this->mealRepository->findOneForUser($id, $user);
        if (null === $meal) {
            return new JsonResponse(['error' => 'Meal not found'], Response::HTTP_NOT_FOUND);
        }

        return new JsonResponse($this->choice->preview($meal));
    }
}
