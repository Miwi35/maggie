<?php

declare(strict_types=1);

namespace Maggie\Cookbook\UseCase;

use Doctrine\ORM\EntityManagerInterface;
use Maggie\Cookbook\Entity\MealSettings;
use Maggie\Cookbook\Event\DefaultGuestsChanged;
use Maggie\Cookbook\Service\MealGuests;
use Symfony\Component\DependencyInjection\Attribute\Autowire;
use Symfony\Component\Messenger\MessageBusInterface;

/**
 * Stores the new default and announces it: the meals still to be eaten that name
 * no guests of their own follow it (MAG-482). A meal already eaten, or with its own
 * number, keeps what it holds.
 */
class UpdateMealSettings
{
    public function __construct(
        private readonly EntityManagerInterface $em,
        private readonly MealGuests $mealGuests,
        #[Autowire(service: 'event.bus')]
        private readonly MessageBusInterface $eventBus,
    ) {
    }

    public function execute(MealSettings $settings, int $previousDefault): MealSettings
    {
        $this->em->flush();
        $this->mealGuests->forget($settings->getUser());

        if ($settings->getDefaultGuests() !== $previousDefault) {
            $this->eventBus->dispatch(new DefaultGuestsChanged((string) $settings->getUser()->getId(), $previousDefault));
        }

        return $settings;
    }
}
