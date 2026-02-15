<?php

declare(strict_types=1);

namespace Maggie\Proaction\MessageHandler;

use Maggie\Proaction\Message\DeleteProactionCommand;
use Maggie\Proaction\Repository\ProactionRepository;
use Maggie\Proaction\UseCase\DeleteProaction;
use Symfony\Component\Messenger\Attribute\AsMessageHandler;

#[AsMessageHandler]
class DeleteProactionHandler
{
    public function __construct(
        private readonly DeleteProaction $deleteProaction,
        private readonly ProactionRepository $proactionRepository,
    ) {
    }

    public function __invoke(DeleteProactionCommand $command): void
    {
        $proaction = $this->proactionRepository->find($command->proactionId);

        if ($proaction === null) {
            throw new \DomainException("Proaction not found: {$command->proactionId}");
        }

        $this->deleteProaction->execute($proaction);
    }
}
