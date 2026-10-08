<?php

declare(strict_types=1);

namespace Maggie\Cookbook\MessageHandler;

use Doctrine\ORM\EntityManagerInterface;
use Maggie\Cookbook\Entity\Meal;
use Maggie\Cookbook\Message\ChooseMealGroceriesCommand;
use Maggie\Cookbook\Repository\MealRepository;
use Maggie\Cookbook\Service\MealGroceryChoice;
use Maggie\Cookbook\Service\MealGrocerySync;
use Maggie\Core\Repository\UserRepository;
use Maggie\Grocery\Service\GroceryListBroadcaster;
use Symfony\Component\Messenger\Attribute\AsMessageHandler;

#[AsMessageHandler]
class ChooseMealGroceriesHandler
{
    public function __construct(
        private readonly MealRepository $mealRepository,
        private readonly UserRepository $userRepository,
        private readonly MealGroceryChoice $choice,
        private readonly MealGrocerySync $mealGrocerySync,
        private readonly GroceryListBroadcaster $groceryListBroadcaster,
        private readonly EntityManagerInterface $em,
    ) {
    }

    public function __invoke(ChooseMealGroceriesCommand $command): Meal
    {
        $user = $this->userRepository->find($command->userId)
            ?? throw new \DomainException('No user found.');
        $meal = $this->mealRepository->findOneForUser($command->mealId, $user)
            ?? throw new \DomainException("Meal not found: {$command->mealId}");

        $list = $this->mealGrocerySync->syncChoice($meal, $this->choice->chosenLines($meal, $command->chosen));

        // One flush for the lines and the marker: lines committed without the
        // marker would be taken back by the meal's next sync, which would
        // still derive them from the recipes.
        $meal->setGroceryChoiceMadeAt(new \DateTimeImmutable());
        $this->em->flush();

        // The handler returns the meal, so the middleware never sees the list.
        $this->groceryListBroadcaster->broadcast($list);

        return $meal;
    }
}
