<?php

declare(strict_types=1);

namespace Maggie\Notification\Message;

/**
 * Not "CreateDeviceTokenCommand": the Mercure middleware publishes whatever a
 * Create/Update/Delete command returns, and a push token must not travel.
 */
final readonly class RegisterDeviceTokenCommand
{
    public function __construct(
        public string $userId,
        public string $token,
        public string $platform = 'android',
        public ?string $deviceName = null,
    ) {
    }
}
