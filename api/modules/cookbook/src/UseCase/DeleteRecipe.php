<?php

declare(strict_types=1);

namespace Maggie\Cookbook\UseCase;

use Doctrine\ORM\EntityManagerInterface;
use Maggie\Cookbook\Entity\Recipe;

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
