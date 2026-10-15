<?php

declare(strict_types=1);

namespace Maggie\Cookbook\Service;

use Maggie\Cookbook\Entity\Utensil;
use Maggie\Cookbook\Exception\UnknownUtensilException;
use Maggie\Cookbook\Repository\UtensilRepository;
use Maggie\Cookbook\UseCase\CreateUtensil;
use Maggie\Core\Entity\User;

/**
 * Turns the utensils a recipe is written with into the user's own (MAG-489):
 * an id must be one of theirs, a name is found in their catalogue — whatever
 * its case — or added to it. Nothing is flushed: the recipe's flush does it.
 */
class RecipeUtensils
{
    public function __construct(
        private readonly UtensilRepository $utensilRepository,
        private readonly CreateUtensil $createUtensil,
    ) {
    }

    /**
     * @param list<array{id: string}|array{name: string}> $refs
     *
     * @return list<Utensil>
     */
    public function resolve(array $refs, User $user): array
    {
        $utensils = [];
        // Two new names of one utensil in the same list ("Poêle", "poêle") are one.
        $created = [];
        foreach ($refs as $ref) {
            if (isset($ref['id'])) {
                $utensil = $this->utensilRepository->findOneForUser($ref['id'], $user)
                    ?? throw new UnknownUtensilException($ref['id']);
            } else {
                $key = mb_strtolower(Utensil::normalizeName($ref['name']));
                $utensil = $created[$key] ??= $this->createUtensil->findOrCreate($user, $ref['name']);
            }
            $utensils[(string) $utensil->getId()] = $utensil;
        }

        return array_values($utensils);
    }
}
