<?php

declare(strict_types=1);

namespace Maggie\Cookbook\UseCase;

use Doctrine\ORM\EntityManagerInterface;
use Maggie\Cookbook\Entity\Utensil;
use Maggie\Cookbook\Exception\UtensilNameTakenException;
use Maggie\Cookbook\Repository\RecipeRepository;
use Maggie\Cookbook\Repository\UtensilRepository;
use Maggie\Core\Projection\WorkCollector;

class RenameUtensil
{
    public function __construct(
        private readonly EntityManagerInterface $em,
        private readonly UtensilRepository $utensilRepository,
        private readonly RecipeRepository $recipeRepository,
        private readonly WorkCollector $projection,
    ) {
    }

    public function execute(Utensil $utensil, string $name): Utensil
    {
        $other = $this->utensilRepository->findByNameAndUser($name, $utensil->getUser());
        if (null !== $other && !$other->getId()->equals($utensil->getId())) {
            throw new UtensilNameTakenException(Utensil::normalizeName($name));
        }

        $utensil->setName($name);
        $this->em->flush();

        // The recipes made with it show its name: their open sheets must hear of the new one.
        foreach ($this->recipeRepository->findByUtensil($utensil) as $recipe) {
            $this->projection->touched($recipe, ['utensils']);
        }

        return $utensil;
    }
}
