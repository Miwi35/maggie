<?php

declare(strict_types=1);

namespace Maggie\Memory\MessageHandler;

use Maggie\Memory\Message\DeleteMemoryCommand;
use Maggie\Memory\Repository\MemoryRepository;
use Maggie\Memory\UseCase\DeleteMemory;
use Symfony\Component\Messenger\Attribute\AsMessageHandler;

#[AsMessageHandler]
class DeleteMemoryHandler
{
    public function __construct(
        private readonly DeleteMemory $deleteMemory,
        private readonly MemoryRepository $memoryRepository,
    ) {
    }

    public function __invoke(DeleteMemoryCommand $command): void
    {
        $memory = $this->memoryRepository->find($command->memoryId);

        if ($memory === null) {
            throw new \DomainException("Memory not found: {$command->memoryId}");
        }

        $this->deleteMemory->execute($memory);
    }
}
