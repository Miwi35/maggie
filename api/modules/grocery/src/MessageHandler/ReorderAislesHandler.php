<?php

declare(strict_types=1);

namespace Maggie\Grocery\MessageHandler;

use Maggie\Core\Repository\UserRepository;
use Maggie\Grocery\Entity\Aisle;
use Maggie\Grocery\Message\ReorderAislesCommand;
use Maggie\Grocery\Service\OwnedAisles;
use Maggie\Grocery\UseCase\ReorderAisles;
use Symfony\Component\Messenger\Attribute\AsMessageHandler;

#[AsMessageHandler]
class ReorderAislesHandler
{
    public function __construct(
        private readonly ReorderAisles $reorderAisles,
        private readonly OwnedAisles $ownedAisles,
        private readonly UserRepository $userRepository,
    ) {
    }

    /** @return Aisle[] */
    public function __invoke(ReorderAislesCommand $command): array
    {
        $user = $this->userRepository->find($command->userId)
            ?? throw new \DomainException('User not found.');

        $store = $this->ownedAisles->store($user, $command->storeId);

        return $this->reorderAisles->execute($store, $command->aisleIds);
    }
}
