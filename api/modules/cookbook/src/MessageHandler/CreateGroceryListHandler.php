<?php

declare(strict_types=1);

namespace Maggie\Cookbook\MessageHandler;

use Maggie\Cookbook\Entity\GroceryList;
use Maggie\Cookbook\Enum\GroceryListStatus;
use Maggie\Cookbook\Message\CreateGroceryListCommand;
use Maggie\Cookbook\UseCase\CreateGroceryList;
use Maggie\Core\Repository\UserRepository;
use Symfony\Component\Messenger\Attribute\AsMessageHandler;

#[AsMessageHandler]
class CreateGroceryListHandler
{
    public function __construct(
        private readonly CreateGroceryList $createGroceryList,
        private readonly UserRepository $userRepository,
    ) {
    }

    public function __invoke(CreateGroceryListCommand $command): GroceryList
    {
        $user = $this->userRepository->find($command->userId)
            ?? throw new \DomainException('User not found.');

        $list = new GroceryList();
        $list->setUser($user);
        $list->setWeekStart(new \DateTimeImmutable($command->weekStart));
        $list->setStatus(GroceryListStatus::from($command->status));

        return $this->createGroceryList->execute($list);
    }
}
