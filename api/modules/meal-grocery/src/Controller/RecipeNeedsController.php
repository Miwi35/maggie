<?php

declare(strict_types=1);

namespace Maggie\MealGrocery\Controller;

use Maggie\Core\Entity\User;
use Maggie\MealGrocery\Service\RecipeNeeds;
use Symfony\Bundle\SecurityBundle\Security;
use Symfony\Component\HttpFoundation\JsonResponse;
use Symfony\Component\HttpFoundation\Request;
use Symfony\Component\HttpFoundation\Response;
use Symfony\Component\Routing\Attribute\Route;

/**
 * « Pour les recettes » (MAG-590): the ingredients the meals of a period
 * need, one entry per meal and product, read live from the meals, their
 * recipes and the stock — no index of its own. `missing=true` keeps those the
 * owner is low on or out of, the set the « Il manque » interruption (MAG-570)
 * and the Accueil card share. {@see RecipeNeedController} answers the same
 * question for one product over the week.
 */
final class RecipeNeedsController
{
    /** Longest period a client may ask for, in days. */
    public const MAX_DAYS = 31;

    public function __construct(
        private readonly Security $security,
        private readonly RecipeNeeds $recipeNeeds,
    ) {
    }

    #[Route('/api/grocery/recipe-needs', name: 'api_grocery_recipe_needs', methods: ['GET'])]
    public function __invoke(Request $request): JsonResponse
    {
        $user = $this->security->getUser();
        if (!$user instanceof User) {
            return new JsonResponse(['error' => 'Unauthorized'], Response::HTTP_UNAUTHORIZED);
        }

        $from = self::day($request->query->get('from'));
        $to = self::day($request->query->get('to'));
        if (null === $from || null === $to) {
            return self::refused('from and to are required, as YYYY-MM-DD.');
        }
        if ($to < $from || $from->modify('+'.(self::MAX_DAYS - 1).' days') < $to) {
            return self::refused(\sprintf('to must be on or after from, within %d days.', self::MAX_DAYS));
        }

        $missing = $request->query->get('missing');
        if (null !== $missing && !\in_array($missing, ['true', 'false'], true)) {
            return self::refused('missing must be true or false.');
        }

        $entries = $this->recipeNeeds->ingredientsPerMeal($user, $from, $to);
        if ('true' === $missing) {
            $entries = array_values(array_filter($entries, static fn (array $entry): bool => $entry['missing']));
        }

        return new JsonResponse(['member' => $entries, 'totalItems' => \count($entries)]);
    }

    private static function day(mixed $value): ?\DateTimeImmutable
    {
        if (!\is_string($value)) {
            return null;
        }

        $day = \DateTimeImmutable::createFromFormat('!Y-m-d', $value);

        return false !== $day && $day->format('Y-m-d') === $value ? $day : null;
    }

    private static function refused(string $message): JsonResponse
    {
        return new JsonResponse(['error' => $message], Response::HTTP_BAD_REQUEST);
    }
}
