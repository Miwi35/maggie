<?php

declare(strict_types=1);

namespace Maggie\Cookbook\MessageHandler;

use Doctrine\ORM\EntityManagerInterface;
use Maggie\Cookbook\Message\GenerateGroceryListCommand;
use Maggie\Cookbook\Service\GroceryGenerationService;
use Maggie\Core\Repository\UserRepository;
use Maggie\Grocery\Entity\GroceryList;
use Symfony\Component\Messenger\Attribute\AsMessageHandler;

#[AsMessageHandler]
class GenerateGroceryListHandler
{
    public function __construct(
        private readonly GroceryGenerationService $groceryGenerationService,
        private readonly UserRepository $userRepository,
        private readonly EntityManagerInterface $em,
    ) {
    }

    public function __invoke(GenerateGroceryListCommand $command): GroceryList
    {
        $user = $this->userRepository->find($command->userId)
            ?? throw new \DomainException('User not found.');

        $timeZone = new \DateTimeZone('Europe/Paris');
        $from = new \DateTimeImmutable($command->fromDate, $timeZone);
        $to = new \DateTimeImmutable($command->toDate.' 23:59:59', $timeZone);

        // Returned on purpose: the Mercure and Elasticsearch middlewares read
        // the handler's result, so this is what gets published and reindexed.
        $list = $this->groceryGenerationService->generate($user, $from, $to);

        $this->em->flush();

        return $list;
    }
}
