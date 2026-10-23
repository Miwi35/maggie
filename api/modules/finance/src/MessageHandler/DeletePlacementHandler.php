<?php

declare(strict_types=1);

namespace Maggie\Finance\MessageHandler;

use Maggie\Finance\Message\DeletePlacementCommand;
use Maggie\Finance\Repository\PlacementRepository;
use Maggie\Finance\UseCase\DeletePlacement;
use Symfony\Component\Messenger\Attribute\AsMessageHandler;

#[AsMessageHandler]
class DeletePlacementHandler
{
    public function __construct(
        private readonly DeletePlacement $deletePlacement,
        private readonly PlacementRepository $placementRepository,
    ) {
    }

    public function __invoke(DeletePlacementCommand $command): void
    {
        $placement = $this->placementRepository->findOneOfUser($command->userId, $command->placementId)
            ?? throw new \DomainException("Placement not found: {$command->placementId}");

        $this->deletePlacement->execute($placement);
    }
}
