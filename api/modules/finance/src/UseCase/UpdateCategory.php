<?php

declare(strict_types=1);

namespace Maggie\Finance\UseCase;

use Doctrine\ORM\EntityManagerInterface;
use Maggie\Finance\Entity\Category;

class UpdateCategory
{
    public function __construct(
        private readonly EntityManagerInterface $em,
    ) {
    }

    public function execute(Category $category): Category
    {
        $this->em->flush();

        return $category;
    }
}
