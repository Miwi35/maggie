<?php

declare(strict_types=1);

namespace Maggie\Memory\MessageHandler;

use Maggie\Core\Repository\UserRepository;
use Maggie\Memory\Entity\Memory;
use Maggie\Memory\Entity\MemoryType;
use Maggie\Memory\Message\StoreMemoryCommand;
use Maggie\Memory\UseCase\StoreMemory;
use Symfony\Component\Messenger\Attribute\AsMessageHandler;

#[AsMessageHandler]
class StoreMemoryHandler
{
    public function __construct(
        private readonly StoreMemory $storeMemory,
        private readonly UserRepository $userRepository,
    ) {
    }

    public function __invoke(StoreMemoryCommand $command): Memory
    {
        $type = MemoryType::tryFrom($command->type);
        if ($type === null) {
            throw new \DomainException("Invalid memory type: {$command->type}. Valid: factual, episodic");
        }

        if ($command->userId !== null) {
            $user = $this->userRepository->find($command->userId);
        } else {
            $users = $this->userRepository->findAll();
            $user = $users[0] ?? null;
        }

        if ($user === null) {
            throw new \DomainException('No user found.');
        }

        $metadata = null;
        if ($command->metadata !== null) {
            $metadata = json_decode($command->metadata, true, 512, JSON_THROW_ON_ERROR);
        }

        $memory = new Memory();
        $memory->setUser($user);
        $memory->setType($type);
        $memory->setContent($command->content);
        $memory->setMetadata($metadata);

        return $this->storeMemory->execute($memory);
    }
}
