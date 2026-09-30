<?php

declare(strict_types=1);

namespace Maggie\Cookbook\MessageHandler;

use Doctrine\ORM\EntityManagerInterface;
use Maggie\Cookbook\Message\GenerateGroceryListCommand;
use Maggie\Cookbook\Service\GroceryGenerationService;
use Maggie\Core\Repository\UserRepository;
use Maggie\Grocery\Entity\GroceryList;
use Maggie\Grocery\Repository\GroceryListRepository;
use Symfony\Component\Messenger\Attribute\AsMessageHandler;

#[AsMessageHandler]
class GenerateGroceryListHandler
{
    public function __construct(
        private readonly GroceryGenerationService $groceryGenerationService,
        private readonly GroceryListRepository $groceryListRepository,
        private readonly UserRepository $userRepository,
        private readonly EntityManagerInterface $em,
    ) {
    }

    public function __invoke(GenerateGroceryListCommand $command): GroceryList
    {
        $user = $this->userRepository->find($command->userId)
            ?? throw new \DomainException('User not found.');

        $from = new \DateTimeImmutable($command->fromDate);
        $to = new \DateTimeImmutable($command->toDate);

        $list = $this->groceryListRepository->findOrCreateForUser($user);

        $items = $this->groceryGenerationService->generate($user, $from, $to);
        foreach ($items as $item) {
            $list->addItem($item);
        }

        $list->setUpdatedAt(new \DateTimeImmutable());
        $this->em->flush();

        return $list;
    }
}
