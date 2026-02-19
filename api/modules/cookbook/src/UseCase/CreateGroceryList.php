<?php

declare(strict_types=1);

namespace Maggie\Cookbook\UseCase;

use Maggie\Cookbook\Entity\GroceryList;
use Doctrine\ORM\EntityManagerInterface;

class CreateGroceryList
{
    public function __construct(
        private readonly EntityManagerInterface $em,
    ) {
    }

    public function execute(GroceryList $list): GroceryList
    {
        $this->em->persist($list);
        $this->em->flush();

        return $list;
    }
}
