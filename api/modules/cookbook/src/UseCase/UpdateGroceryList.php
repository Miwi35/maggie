<?php

declare(strict_types=1);

namespace Maggie\Cookbook\UseCase;

use Maggie\Cookbook\Entity\GroceryList;
use Doctrine\ORM\EntityManagerInterface;

class UpdateGroceryList
{
    public function __construct(
        private readonly EntityManagerInterface $em,
    ) {
    }

    public function execute(GroceryList $list): GroceryList
    {
        $this->em->flush();

        return $list;
    }
}
