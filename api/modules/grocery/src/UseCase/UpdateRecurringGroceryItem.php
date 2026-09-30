<?php

declare(strict_types=1);

namespace Maggie\Grocery\UseCase;

use Doctrine\ORM\EntityManagerInterface;
use Maggie\Grocery\Entity\RecurringGroceryItem;

class UpdateRecurringGroceryItem
{
    public function __construct(
        private readonly EntityManagerInterface $em,
    ) {
    }

    public function execute(RecurringGroceryItem $item): RecurringGroceryItem
    {
        $this->em->flush();

        return $item;
    }
}
