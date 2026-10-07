<?php

declare(strict_types=1);

namespace Maggie\Notification\MessageHandler;

use Maggie\Notification\Message\SendPushNotificationCommand;
use Maggie\Notification\Push\FcmClient;
use Maggie\Notification\Push\FcmSendResult;
use Maggie\Notification\Push\FcmUnavailableException;
use Maggie\Notification\Push\PushMessageFactory;
use Maggie\Notification\Repository\DeviceTokenRepository;
use Maggie\Notification\Repository\NotificationRepository;
use Maggie\Notification\UseCase\UnregisterDeviceToken;
use Psr\Log\LoggerInterface;
use Symfony\Component\Messenger\Attribute\AsMessageHandler;

#[AsMessageHandler]
class SendPushNotificationHandler
{
    public function __construct(
        private readonly FcmClient $fcm,
        private readonly PushMessageFactory $messageFactory,
        private readonly NotificationRepository $notificationRepository,
        private readonly DeviceTokenRepository $deviceTokenRepository,
        private readonly UnregisterDeviceToken $unregisterDeviceToken,
        private readonly LoggerInterface $logger,
    ) {
    }

    /**
     * Every device gets its try before a failure is rethrown for the worker to
     * retry: one unreachable phone must not keep the push from the others. A
     * retry reaches the devices already served again; the message's Android
     * tag makes it replace itself there, and the app dedupes on the id.
     */
    public function __invoke(SendPushNotificationCommand $command): void
    {
        if (!$this->fcm->isConfigured()) {
            $this->logger->info('No FCM key configured: push of notification {id} skipped.', ['id' => $command->notificationId]);

            return;
        }

        $notification = $this->notificationRepository->find($command->notificationId);

        // Deleted or already read by the time the worker got to it: nothing to say.
        if (null === $notification || null !== $notification->getReadAt()) {
            return;
        }

        $failure = null;

        foreach ($this->deviceTokenRepository->findByUser($notification->getUser()) as $deviceToken) {
            try {
                $result = $this->fcm->send($this->messageFactory->build($notification, $deviceToken->getToken()));
            } catch (FcmUnavailableException $e) {
                $failure = $e;

                continue;
            }

            if (FcmSendResult::UnknownToken === $result) {
                $this->unregisterDeviceToken->execute($deviceToken);
            }
        }

        if (null !== $failure) {
            throw $failure;
        }
    }
}
