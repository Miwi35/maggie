<?php

declare(strict_types=1);

namespace Maggie\Proaction\MessageHandler;

use Maggie\Core\Repository\UserRepository;
use Maggie\Proaction\Entity\Proaction;
use Maggie\Proaction\Message\CreateProactionCommand;
use Maggie\Proaction\UseCase\CreateProaction;
use Symfony\Component\Messenger\Attribute\AsMessageHandler;

#[AsMessageHandler]
class CreateProactionHandler
{
    public function __construct(
        private readonly CreateProaction $createProaction,
        private readonly UserRepository $userRepository,
    ) {
    }

    public function __invoke(CreateProactionCommand $command): Proaction
    {
        if ($command->userId !== null) {
            $user = $this->userRepository->find($command->userId);
        } else {
            $users = $this->userRepository->findAll();
            $user = $users[0] ?? null;
        }

        if ($user === null) {
            throw new \DomainException('No user found.');
        }

        $proaction = new Proaction();
        $proaction->setUser($user);
        $proaction->setScheduledAt($command->scheduledAt);
        $proaction->setPrompt($command->prompt);

        return $this->createProaction->execute($proaction);
    }
}
