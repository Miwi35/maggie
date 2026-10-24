<?php

declare(strict_types=1);

namespace Maggie\MealGrocery\MessageHandler;

use Maggie\Cookbook\Entity\Meal;
use Maggie\Core\Repository\UserRepository;
use Maggie\MealGrocery\Message\GenerateGroceryListCommand;
use Maggie\MealGrocery\Service\GroceryGeneration;
use Maggie\MealGrocery\Service\GroceryGenerationService;
use Symfony\Component\Messenger\Attribute\AsMessageHandler;

#[AsMessageHandler]
class GenerateGroceryListHandler
{
    public function __construct(
        private readonly GroceryGenerationService $groceryGenerationService,
        private readonly UserRepository $userRepository,
    ) {
    }

    public function __invoke(GenerateGroceryListCommand $command): GroceryGeneration
    {
        $user = $this->userRepository->find($command->userId)
            ?? throw new \DomainException('User not found.');

        // A range of days, both ends included: a meal is a day (MAG-251), so
        // there is no end-of-day instant to reach for. Read through the same
        // lenient reader `manage_meals action=list` uses, so the two tools
        // cannot drift on what they accept.
        return $this->groceryGenerationService->generate(
            $user,
            Meal::dayOfString($command->fromDate),
            Meal::dayOfString($command->toDate),
        );
    }
}
