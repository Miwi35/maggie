<?php

declare(strict_types=1);

namespace Maggie\MealGrocery\MessageHandler;

use Doctrine\ORM\EntityManagerInterface;
use Maggie\Cookbook\Entity\Meal;
use Maggie\Cookbook\Repository\MealRepository;
use Maggie\Core\Repository\UserRepository;
use Maggie\MealGrocery\Message\ChooseMealGroceriesCommand;
use Maggie\MealGrocery\Service\MealGroceryChoice;
use Maggie\MealGrocery\Service\MealGrocerySync;
use Symfony\Component\Messenger\Attribute\AsMessageHandler;

#[AsMessageHandler]
class ChooseMealGroceriesHandler
{
    public function __construct(
        private readonly MealRepository $mealRepository,
        private readonly UserRepository $userRepository,
        private readonly MealGroceryChoice $choice,
        private readonly MealGrocerySync $mealGrocerySync,
        private readonly EntityManagerInterface $em,
    ) {
    }

    public function __invoke(ChooseMealGroceriesCommand $command): Meal
    {
        $user = $this->userRepository->find($command->userId)
            ?? throw new \DomainException('No user found.');
        $meal = $this->mealRepository->findOneForUser($command->mealId, $user)
            ?? throw new \DomainException("Meal not found: {$command->mealId}");

        $this->mealGrocerySync->syncChoice($meal, $this->choice->chosenLines($meal, $command->chosen));

        // One flush for the lines and the marker, so the choice is never half recorded.
        $meal->setGroceryChoiceMadeAt(new \DateTimeImmutable());
        $this->em->flush();

        return $meal;
    }
}
