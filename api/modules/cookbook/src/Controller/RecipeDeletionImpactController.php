<?php

declare(strict_types=1);

namespace Maggie\Cookbook\Controller;

use Maggie\Cookbook\Entity\Meal;
use Maggie\Cookbook\Repository\MealRepository;
use Maggie\Cookbook\Repository\RecipeRepository;
use Maggie\Core\Entity\User;
use Symfony\Bundle\SecurityBundle\Security;
use Symfony\Component\HttpFoundation\JsonResponse;
use Symfony\Component\HttpFoundation\Response;
use Symfony\Component\Routing\Attribute\Route;
use Symfony\Component\Uid\Ulid;

/** What deleting a recipe would take with it, for the confirmation that precedes it (MAG-289). */
final class RecipeDeletionImpactController
{
    public function __construct(
        private readonly Security $security,
        private readonly RecipeRepository $recipeRepository,
        private readonly MealRepository $mealRepository,
    ) {
    }

    #[Route('/api/recipes/{id}/deletion-impact', name: 'api_recipe_deletion_impact', methods: ['GET'])]
    public function __invoke(string $id): JsonResponse
    {
        $user = $this->security->getUser();
        if (!$user instanceof User) {
            return new JsonResponse(['error' => 'Unauthorized'], Response::HTTP_UNAUTHORIZED);
        }

        $recipe = Ulid::isValid($id) ? $this->recipeRepository->findOneForUser($id, $user) : null;
        if (null === $recipe) {
            return new JsonResponse(['error' => 'Recipe not found'], Response::HTTP_NOT_FOUND);
        }

        $meals = $this->mealRepository->findServedOnlyBy($recipe);

        return new JsonResponse([
            'mealCount' => \count($meals),
            'meals' => array_map(
                static fn (Meal $meal) => ['date' => $meal->getDate()?->format('Y-m-d'), 'slot' => $meal->getSlot()->value],
                $meals,
            ),
        ]);
    }
}
