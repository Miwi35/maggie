<?php

namespace Maggie\Core\MessageHandler;

use Doctrine\ORM\EntityManagerInterface;
use Maggie\Core\Entity\User;
use Maggie\Core\Message\UpdateUserCommand;
use Maggie\Core\Repository\UserRepository;
use Symfony\Component\Messenger\Attribute\AsMessageHandler;

#[AsMessageHandler]
class UpdateUserHandler
{
    public function __construct(
        private readonly UserRepository $userRepository,
        private readonly EntityManagerInterface $entityManager,
    ) {
    }

    public function __invoke(UpdateUserCommand $command): User
    {
        $user = $this->userRepository->find($command->userId);
        if ($user === null) {
            throw new \DomainException("User not found: {$command->userId}");
        }

        if ($command->name !== null) {
            $user->setName($command->name);
        }
        if ($command->avatar !== null) {
            $user->setAvatar($command->avatar);
        } elseif ($command->clears('avatar')) {
            $user->setAvatar(null);
        }

        $this->entityManager->flush();

        return $user;
    }
}
