<?php

declare(strict_types=1);

namespace Maggie\Finance\UseCase;

use Doctrine\ORM\EntityManagerInterface;
use Maggie\Finance\Entity\Category;

class CreateCategory
{
    public function __construct(
        private readonly EntityManagerInterface $em,
    ) {
    }

    public function execute(Category $category): Category
    {
        $this->em->persist($category);
        $this->em->flush();

        return $category;
    }
}
