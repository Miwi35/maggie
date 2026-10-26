<?php

declare(strict_types=1);

namespace Maggie\Notification\Push;

use Maggie\Core\Entity\User;
use Maggie\Notification\Repository\DeviceTokenRepository;
use Maggie\Notification\UseCase\UnregisterDeviceToken;
use Psr\Log\LoggerInterface;

/**
 * Asks every phone of a user for its position (MAG-650), when the one stored is
 * too old to plan a departure with. The phones answer through
 * `PUT /api/me/location`, later: the caller works with what is stored.
 *
 * Best effort: an unreachable FCM is logged, never thrown — a request lost is
 * only a position a little older.
 */
class LocationRequester
{
    public function __construct(
        private readonly FcmClient $fcm,
        private readonly PushMessageFactory $messageFactory,
        private readonly DeviceTokenRepository $deviceTokenRepository,
        private readonly UnregisterDeviceToken $unregisterDeviceToken,
        private readonly LoggerInterface $logger,
    ) {
    }

    /** @return int how many devices FCM accepted the request for */
    public function request(User $user): int
    {
        if (!$this->fcm->isConfigured()) {
            $this->logger->info('No FCM key configured: location request for user {user} skipped.', ['user' => (string) $user->getId()]);

            return 0;
        }

        $sent = 0;

        foreach ($this->deviceTokenRepository->findByUser($user) as $deviceToken) {
            try {
                $result = $this->fcm->send($this->messageFactory->buildLocationRequest($deviceToken->getToken()));
            } catch (FcmUnavailableException $e) {
                $this->logger->warning('Location request for user {user} not sent: {error}', [
                    'user' => (string) $user->getId(),
                    'error' => $e->getMessage(),
                ]);

                continue;
            }

            if (FcmSendResult::UnknownToken === $result) {
                $this->unregisterDeviceToken->execute($deviceToken);

                continue;
            }

            if (FcmSendResult::Sent === $result) {
                ++$sent;
            }
        }

        return $sent;
    }
}
