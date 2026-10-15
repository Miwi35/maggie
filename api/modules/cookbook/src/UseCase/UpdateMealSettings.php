<?php

declare(strict_types=1);

namespace Maggie\Cookbook\UseCase;

use Doctrine\ORM\EntityManagerInterface;
use Maggie\Cookbook\Entity\MealSettings;
use Maggie\Cookbook\Message\RescaleMealGroceriesCommand;
use Maggie\Cookbook\Repository\MealRepository;
use Maggie\Cookbook\Service\MealGuests;
use Symfony\Component\Messenger\MessageBusInterface;

/**
 * Stores the new default and scales the grocery needs of the meals it changes:
 * those still to be eaten that name no guests of their own (MAG-482). A meal
 * already eaten, or with its own number, keeps what it holds.
 */
class UpdateMealSettings
{
    public function __construct(
        private readonly EntityManagerInterface $em,
        private readonly MealRepository $mealRepository,
        private readonly MealGuests $mealGuests,
        private readonly MessageBusInterface $bus,
    ) {
    }

    public function execute(MealSettings $settings, int $previousDefault): MealSettings
    {
        $this->em->flush();
        $this->mealGuests->forget($settings->getUser());

        if ($settings->getDefaultGuests() === $previousDefault) {
            return $settings;
        }

        foreach ($this->mealRepository->findUpcomingOnDefaultGuestsForUser($settings->getUser()) as $meal) {
            $this->bus->dispatch(new RescaleMealGroceriesCommand((string) $meal->getId(), $previousDefault));
        }

        return $settings;
    }
}
