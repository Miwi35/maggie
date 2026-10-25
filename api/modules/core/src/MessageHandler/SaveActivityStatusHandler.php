<?php

declare(strict_types=1);

namespace Maggie\Core\MessageHandler;

use Doctrine\ORM\EntityManagerInterface;
use Maggie\Core\Entity\ActivityStatus;
use Maggie\Core\Message\SaveActivityStatusCommand;
use Maggie\Core\Repository\ActivityStatusRepository;
use Maggie\Core\Repository\UserRepository;
use Psr\Clock\ClockInterface;
use Symfony\Component\Messenger\Attribute\AsMessageHandler;

/**
 * One status per user and activity: the first write lays it down, the next
 * ones replace origin and state. Another origin is another activity, so it
 * starts again from now.
 */
#[AsMessageHandler]
class SaveActivityStatusHandler
{
    public function __construct(
        private readonly ActivityStatusRepository $repository,
        private readonly UserRepository $userRepository,
        private readonly EntityManagerInterface $em,
        private readonly ClockInterface $clock,
    ) {
    }

    public function __invoke(SaveActivityStatusCommand $command): ActivityStatus
    {
        $user = $this->userRepository->find($command->userId)
            ?? throw new \DomainException('User not found.');
        $now = $this->clock->now();

        $status = $this->repository->findOneByUserAndActivity($user, $command->activity);
        if (null === $status) {
            $status = (new ActivityStatus())->setUser($user)->setActivity($command->activity)->setStartedAt($now);
            $this->em->persist($status);
        } elseif ($status->getOriginKind() !== $command->originKind || $status->getOriginId() !== $command->originId) {
            $status->setStartedAt($now);
        }

        $status->setOrigin($command->originKind, $command->originId);
        $status->setState($command->state);
        $status->setUpdatedAt($now);

        $this->em->flush();

        return $status;
    }
}
