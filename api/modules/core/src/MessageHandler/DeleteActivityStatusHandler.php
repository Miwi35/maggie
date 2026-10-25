<?php

declare(strict_types=1);

namespace Maggie\Core\MessageHandler;

use Doctrine\ORM\EntityManagerInterface;
use Maggie\Core\Message\DeleteActivityStatusCommand;
use Maggie\Core\Repository\ActivityStatusRepository;
use Maggie\Core\Repository\UserRepository;
use Symfony\Component\Messenger\Attribute\AsMessageHandler;

/** Removes the status if there is one: ending an activity twice is not an error. */
#[AsMessageHandler]
class DeleteActivityStatusHandler
{
    public function __construct(
        private readonly ActivityStatusRepository $repository,
        private readonly UserRepository $userRepository,
        private readonly EntityManagerInterface $em,
    ) {
    }

    public function __invoke(DeleteActivityStatusCommand $command): void
    {
        $user = $this->userRepository->find($command->userId)
            ?? throw new \DomainException('User not found.');

        $status = $this->repository->findOneByUserAndActivity($user, $command->activity);
        if (null === $status) {
            return;
        }

        $this->em->remove($status);
        $this->em->flush();
    }
}
