<?php

declare(strict_types=1);

namespace Maggie\Core\MessageHandler;

use Doctrine\ORM\EntityManagerInterface;
use Maggie\Core\Entity\User;
use Maggie\Core\Entity\UserPreference;
use Maggie\Core\Message\SetSickCommand;
use Maggie\Core\Repository\UserPreferenceRepository;
use Psr\Clock\ClockInterface;
use Symfony\Component\Messenger\Attribute\AsMessageHandler;

/**
 * Sick from today, in the user's timezone, until they say it is over (MAG-810).
 * Said again while sick, the first day stays: the illness did not start again.
 */
#[AsMessageHandler]
class SetSickHandler
{
    public function __construct(
        private readonly UserPreferenceRepository $repository,
        private readonly EntityManagerInterface $entityManager,
        private readonly ClockInterface $clock,
    ) {
    }

    public function __invoke(SetSickCommand $command): UserPreference
    {
        $user = $this->entityManager->find(User::class, $command->userId)
            ?? throw new \DomainException("User not found: {$command->userId}");

        $pref = $this->repository->findOneByUser($user);
        if (null === $pref) {
            $pref = (new UserPreference())->setUser($user);
            $this->entityManager->persist($pref);
        }

        if (!$command->sick) {
            $pref->setSickSince(null);
        } elseif (null === $pref->getSickSince()) {
            $today = $this->clock->now()->setTimezone(new \DateTimeZone($pref->getTimezone()))->format('Y-m-d');
            $pref->setSickSince(new \DateTimeImmutable($today));
        }

        $pref->setUpdatedAt(new \DateTimeImmutable());
        $this->entityManager->flush();

        return $pref;
    }
}
