<?php

declare(strict_types=1);

namespace Maggie\Notification\MessageHandler;

use Maggie\Core\Repository\UserRepository;
use Maggie\Notification\Entity\DeviceToken;
use Maggie\Notification\Enum\DevicePlatform;
use Maggie\Notification\Message\RegisterDeviceTokenCommand;
use Maggie\Notification\UseCase\RegisterDeviceToken;
use Symfony\Component\Messenger\Attribute\AsMessageHandler;

#[AsMessageHandler]
class RegisterDeviceTokenHandler
{
    public function __construct(
        private readonly RegisterDeviceToken $registerDeviceToken,
        private readonly UserRepository $userRepository,
    ) {
    }

    public function __invoke(RegisterDeviceTokenCommand $command): DeviceToken
    {
        $user = $this->userRepository->find($command->userId)
            ?? throw new \DomainException('User not found.');

        $platform = DevicePlatform::tryFrom($command->platform)
            ?? throw new \DomainException(sprintf('Unknown platform "%s".', $command->platform));

        return $this->registerDeviceToken->execute($user, $command->token, $platform, $command->deviceName);
    }
}
