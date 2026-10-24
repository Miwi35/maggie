<?php

declare(strict_types=1);

namespace Maggie\MealGrocery\Controller;

use Doctrine\ORM\EntityManagerInterface;
use Maggie\Core\Entity\User;
use Maggie\Grocery\Entity\Product;
use Maggie\Grocery\Service\DesiredQuantity;
use Maggie\MealGrocery\Service\RecipeNeeds;
use Symfony\Bundle\SecurityBundle\Security;
use Symfony\Component\HttpFoundation\JsonResponse;
use Symfony\Component\HttpFoundation\Response;
use Symfony\Component\Routing\Attribute\Route;
use Symfony\Component\Uid\Ulid;

/**
 * « Pour les recettes de la semaine » (MAG-573): what the meals planned over
 * the next seven days need of a product, read by the add sheet before the
 * item is added. `recipeNeed` is the one figure to show (the need in the unit
 * of the pack's content), `needs` every unit it is asked in, `meals` the
 * detail by meal.
 */
final class RecipeNeedController
{
    public function __construct(
        private readonly Security $security,
        private readonly EntityManagerInterface $em,
        private readonly RecipeNeeds $recipeNeeds,
        private readonly DesiredQuantity $desired,
    ) {
    }

    #[Route('/api/grocery/recipe-need/{productId}', name: 'api_grocery_recipe_need', methods: ['GET'])]
    public function __invoke(string $productId): JsonResponse
    {
        $user = $this->security->getUser();
        if (!$user instanceof User) {
            return new JsonResponse(['error' => 'Unauthorized'], Response::HTTP_UNAUTHORIZED);
        }

        $product = Ulid::isValid($productId) ? $this->em->getRepository(Product::class)->find(Ulid::fromString($productId)) : null;
        if (!$product instanceof Product || (string) $product->getUser()->getId() !== (string) $user->getId()) {
            return new JsonResponse(['error' => 'Product not found.'], Response::HTTP_NOT_FOUND);
        }

        $need = $this->recipeNeeds->forProduct($user, $product);

        return new JsonResponse([
            'productId' => (string) $product->getId(),
            'days' => RecipeNeeds::WEEK_DAYS,
            'recipeNeed' => $this->desired->headline($product, $need['needs']),
            'needs' => $need['needs'],
            'meals' => $need['meals'],
        ]);
    }
}
