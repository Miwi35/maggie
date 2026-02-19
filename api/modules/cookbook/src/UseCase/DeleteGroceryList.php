<?php

declare(strict_types=1);

namespace Maggie\Cookbook\UseCase;

use Maggie\Cookbook\Entity\GroceryList;
use Doctrine\ORM\EntityManagerInterface;

class DeleteGroceryList
{
    public function __construct(
        private readonly EntityManagerInterface $em,
    ) {
    }

    public function execute(GroceryList $list): void
    {
        $this->em->remove($list);
        $this->em->flush();
    }
}
