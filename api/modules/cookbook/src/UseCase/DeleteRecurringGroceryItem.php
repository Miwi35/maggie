<?php

declare(strict_types=1);

namespace Maggie\Cookbook\UseCase;

use Maggie\Cookbook\Entity\RecurringGroceryItem;
use Doctrine\ORM\EntityManagerInterface;

class DeleteRecurringGroceryItem
{
    public function __construct(
        private readonly EntityManagerInterface $em,
    ) {
    }

    public function execute(RecurringGroceryItem $item): void
    {
        $this->em->remove($item);
        $this->em->flush();
    }
}
