<?php

declare(strict_types=1);

namespace Maggie\Cookbook\UseCase;

use Doctrine\ORM\EntityManagerInterface;
use Maggie\Cookbook\Entity\Meal;
use Maggie\Cookbook\Service\ShortageAlerts;

class DeleteMeal
{
    public function __construct(
        private readonly EntityManagerInterface $em,
        private readonly ShortageAlerts $shortageAlerts,
    ) {
    }

    public function execute(Meal $meal): void
    {
        $this->shortageAlerts->forgetMeal($meal);
        $this->em->remove($meal);
        $this->em->flush();
    }
}
