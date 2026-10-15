<?php

declare(strict_types=1);

namespace Maggie\Cookbook\MessageHandler;

use Maggie\Cookbook\Entity\Utensil;
use Maggie\Cookbook\Message\CreateUtensilCommand;
use Maggie\Cookbook\UseCase\CreateUtensil;
use Maggie\Core\Repository\UserRepository;
use Symfony\Component\Messenger\Attribute\AsMessageHandler;

#[AsMessageHandler]
class CreateUtensilHandler
{
    public function __construct(
        private readonly CreateUtensil $createUtensil,
        private readonly UserRepository $userRepository,
    ) {
    }

    public function __invoke(CreateUtensilCommand $command): Utensil
    {
        $user = $this->userRepository->find($command->userId)
            ?? throw new \DomainException('User not found.');

        return $this->createUtensil->execute($user, $command->name);
    }
}
