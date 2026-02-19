<?php

declare(strict_types=1);

namespace Maggie\Cookbook\UseCase;

use Maggie\Cookbook\Entity\Recipe;
use Doctrine\ORM\EntityManagerInterface;

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
