<?php

declare(strict_types=1);

namespace Maggie\Notification\Message;

/** Not "DeleteDeviceTokenCommand", for the reason given on RegisterDeviceTokenCommand. */
final readonly class UnregisterDeviceTokenCommand
{
    public function __construct(
        public string $userId,
        public string $token,
    ) {
    }
}
