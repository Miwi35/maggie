<?php

declare(strict_types=1);

namespace Maggie\Cookbook\UseCase;

use Doctrine\ORM\EntityManagerInterface;
use Maggie\Cookbook\Entity\Utensil;
use Maggie\Cookbook\Repository\RecipeRepository;

class DeleteUtensil
{
    public function __construct(
        private readonly EntityManagerInterface $em,
        private readonly RecipeRepository $recipeRepository,
    ) {
    }

    public function execute(Utensil $utensil): void
    {
        // The foreign key would drop the links by itself, but then no recipe is
        // republished nor reindexed (the projection reads Doctrine's change set):
        // the recipes stay, without the utensil.
        foreach ($this->recipeRepository->findByUtensil($utensil) as $recipe) {
            $recipe->removeUtensil($utensil);
        }

        $this->em->remove($utensil);
        $this->em->flush();
    }
}
