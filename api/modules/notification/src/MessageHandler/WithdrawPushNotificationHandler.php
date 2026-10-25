<?php

declare(strict_types=1);

namespace Maggie\Notification\MessageHandler;

use Maggie\Notification\Message\WithdrawPushNotificationCommand;
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
class WithdrawPushNotificationHandler
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

    /** Like the push itself: every device gets its try before a failure is rethrown for a retry. */
    public function __invoke(WithdrawPushNotificationCommand $command): void
    {
        if (!$this->fcm->isConfigured()) {
            $this->logger->info('No FCM key configured: withdrawal of notification {id} skipped.', ['id' => $command->notificationId]);

            return;
        }

        $notification = $this->notificationRepository->find($command->notificationId);
        if (null === $notification) {
            return;
        }

        $failure = null;

        foreach ($this->deviceTokenRepository->findByUser($notification->getUser()) as $deviceToken) {
            try {
                $result = $this->fcm->send($this->messageFactory->buildWithdrawal($notification, $deviceToken->getToken()));
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
