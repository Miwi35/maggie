<?php

declare(strict_types=1);

namespace Maggie\Cookbook\UseCase;

use Doctrine\ORM\EntityManagerInterface;
use Maggie\Cookbook\Entity\Recipe;

class CreateRecipe
{
    public function __construct(
        private readonly EntityManagerInterface $em,
    ) {
    }

    public function execute(Recipe $recipe): Recipe
    {
        $this->em->persist($recipe);
        $this->em->flush();

        return $recipe;
    }
}
