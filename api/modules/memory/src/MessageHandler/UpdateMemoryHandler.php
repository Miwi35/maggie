<?php

declare(strict_types=1);

namespace Maggie\Memory\MessageHandler;

use Maggie\Memory\Entity\Memory;
use Maggie\Memory\Message\UpdateMemoryCommand;
use Maggie\Memory\Repository\MemoryRepository;
use Maggie\Memory\UseCase\UpdateMemory;
use Symfony\Component\Messenger\Attribute\AsMessageHandler;

#[AsMessageHandler]
class UpdateMemoryHandler
{
    public function __construct(
        private readonly UpdateMemory $updateMemory,
        private readonly MemoryRepository $memoryRepository,
    ) {
    }

    public function __invoke(UpdateMemoryCommand $command): Memory
    {
        $memory = $this->memoryRepository->find($command->memoryId);

        if ($memory === null) {
            throw new \DomainException("Memory not found: {$command->memoryId}");
        }

        $memory->setContent($command->content);

        return $this->updateMemory->execute($memory);
    }
}
