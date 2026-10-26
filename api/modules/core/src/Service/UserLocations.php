<?php

declare(strict_types=1);

namespace Maggie\Core\Service;

use Doctrine\ORM\EntityManagerInterface;
use Maggie\Core\Entity\User;
use Maggie\Core\Entity\UserLocation;
use Maggie\Core\Repository\UserLocationRepository;

/** The single place a user's location row is written: created on first use, then overwritten (MAG-650). */
final class UserLocations
{
    public function __construct(
        private readonly UserLocationRepository $repository,
        private readonly EntityManagerInterface $entityManager,
    ) {
    }

    /** @param callable(UserLocation): mixed $change */
    public function save(string $userId, callable $change): UserLocation
    {
        $user = $this->entityManager->find(User::class, $userId);
        if (null === $user) {
            throw new \DomainException("User not found: {$userId}");
        }

        $location = $this->repository->findOneByUser($user);
        if (null === $location) {
            $location = (new UserLocation())->setUser($user);
            $this->entityManager->persist($location);
        }

        $change($location);
        $this->entityManager->flush();

        return $location;
    }
}
