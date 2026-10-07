<?php

declare(strict_types=1);

namespace Maggie\Notification\UseCase;

use Doctrine\ORM\EntityManagerInterface;
use Maggie\Core\Entity\User;
use Maggie\Notification\Entity\DeviceToken;
use Maggie\Notification\Enum\DevicePlatform;
use Maggie\Notification\Repository\DeviceTokenRepository;

class RegisterDeviceToken
{
    public function __construct(
        private readonly EntityManagerInterface $em,
        private readonly DeviceTokenRepository $repository,
    ) {
    }

    /**
     * Idempotent: the app sends its token at every login and every refresh.
     * A token already known moves to whoever registers it now — the phone
     * changed hands, or someone else logged in on it — so a push never reaches
     * the previous account's device.
     */
    public function execute(User $user, string $token, DevicePlatform $platform, ?string $deviceName): DeviceToken
    {
        $deviceToken = $this->repository->findOneByToken($token);

        if (null === $deviceToken) {
            $deviceToken = (new DeviceToken())->setToken($token);
            $this->em->persist($deviceToken);
        }

        $deviceToken
            ->setUser($user)
            ->setPlatform($platform)
            ->setDeviceName($deviceName)
            ->setLastSeenAt(new \DateTimeImmutable());

        $this->em->flush();

        return $deviceToken;
    }
}
