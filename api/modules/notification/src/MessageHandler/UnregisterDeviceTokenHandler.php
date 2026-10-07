<?php

declare(strict_types=1);

namespace Maggie\Notification\MessageHandler;

use Maggie\Notification\Message\UnregisterDeviceTokenCommand;
use Maggie\Notification\Repository\DeviceTokenRepository;
use Maggie\Notification\UseCase\UnregisterDeviceToken;
use Symfony\Component\Messenger\Attribute\AsMessageHandler;

#[AsMessageHandler]
class UnregisterDeviceTokenHandler
{
    public function __construct(
        private readonly UnregisterDeviceToken $unregisterDeviceToken,
        private readonly DeviceTokenRepository $repository,
    ) {
    }

    /**
     * Idempotent, like a logout: a token that is unknown, or registered to
     * someone else, is left alone without an error.
     */
    public function __invoke(UnregisterDeviceTokenCommand $command): void
    {
        $deviceToken = $this->repository->findOneByToken($command->token);

        if (null === $deviceToken || (string) $deviceToken->getUser()->getId() !== $command->userId) {
            return;
        }

        $this->unregisterDeviceToken->execute($deviceToken);
    }
}
