<?php

declare(strict_types=1);

namespace Maggie\Cookbook\MessageHandler;

use Maggie\Cookbook\Entity\GroceryList;
use Maggie\Cookbook\Enum\GroceryListStatus;
use Maggie\Cookbook\Message\GenerateGroceryListCommand;
use Maggie\Cookbook\Service\GroceryGenerationService;
use Maggie\Cookbook\UseCase\CreateGroceryList;
use Maggie\Core\Repository\UserRepository;
use Symfony\Component\Messenger\Attribute\AsMessageHandler;

#[AsMessageHandler]
class GenerateGroceryListHandler
{
    public function __construct(
        private readonly GroceryGenerationService $groceryGenerationService,
        private readonly CreateGroceryList $createGroceryList,
        private readonly UserRepository $userRepository,
    ) {
    }

    public function __invoke(GenerateGroceryListCommand $command): GroceryList
    {
        $user = $this->userRepository->find($command->userId)
            ?? throw new \DomainException('User not found.');

        $from = new \DateTimeImmutable($command->fromDate);
        $to = new \DateTimeImmutable($command->toDate);

        $list = new GroceryList();
        $list->setUser($user);
        $list->setWeekStart($from);
        $list->setStatus(GroceryListStatus::Active);

        $items = $this->groceryGenerationService->generate($user, $from, $to);
        foreach ($items as $item) {
            $list->addItem($item);
        }

        return $this->createGroceryList->execute($list);
    }
}
