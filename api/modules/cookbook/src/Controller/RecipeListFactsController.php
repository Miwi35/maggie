<?php

declare(strict_types=1);

namespace Maggie\Cookbook\Controller;

use Maggie\Cookbook\Repository\MealRepository;
use Maggie\Cookbook\Repository\RecipeRepository;
use Maggie\Cookbook\Service\RecipeNutrition;
use Maggie\Core\Entity\User;
use Symfony\Bundle\SecurityBundle\Security;
use Symfony\Component\HttpFoundation\JsonResponse;
use Symfony\Component\HttpFoundation\Response;
use Symfony\Component\Routing\Attribute\Route;

/**
 * What the table of recipes shows beside each recipe and the recipe does not carry (MAG-629): the
 * day of its last meal and the kcal of one serving. One answer for the whole table, by recipe id.
 */
final class RecipeListFactsController
{
    public function __construct(
        private readonly Security $security,
        private readonly RecipeRepository $recipeRepository,
        private readonly MealRepository $mealRepository,
    ) {
    }

    #[Route('/api/recipe-list-facts', name: 'api_recipe_list_facts', methods: ['GET'])]
    public function __invoke(): JsonResponse
    {
        $user = $this->security->getUser();
        if (!$user instanceof User) {
            return new JsonResponse(['error' => 'Unauthorized'], Response::HTTP_UNAUTHORIZED);
        }

        $lastMeals = $this->mealRepository->findLastEatenDatesByRecipeForUser($user);

        $recipes = [];
        foreach ($this->recipeRepository->findAllWithIngredientsForUser($user) as $recipe) {
            $id = (string) $recipe->getId();
            $recipes[$id] = [
                'lastMealDate' => ($lastMeals[$id] ?? null)?->format('Y-m-d'),
                'kcalPerServing' => RecipeNutrition::kcalPerServing($recipe),
            ];
        }

        return new JsonResponse(['recipes' => (object) $recipes]);
    }
}
