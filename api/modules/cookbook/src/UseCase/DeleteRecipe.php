<?php

declare(strict_types=1);

namespace Maggie\Cookbook\UseCase;

use Maggie\Cookbook\Entity\Recipe;
use Doctrine\ORM\EntityManagerInterface;

class DeleteRecipe
{
    public function __construct(
        private readonly EntityManagerInterface $em,
    ) {
    }

    public function execute(Recipe $recipe): void
    {
        $this->em->remove($recipe);
        $this->em->flush();
    }
}
