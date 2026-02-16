<?php

declare(strict_types=1);

namespace Maggie\Notification\MessageHandler;

use Maggie\Notification\Message\DeleteNotificationCommand;
use Maggie\Notification\Repository\NotificationRepository;
use Maggie\Notification\UseCase\DeleteNotification;
use Symfony\Component\Messenger\Attribute\AsMessageHandler;

#[AsMessageHandler]
class DeleteNotificationHandler
{
    public function __construct(
        private readonly DeleteNotification $deleteNotification,
        private readonly NotificationRepository $notificationRepository,
    ) {
    }

    public function __invoke(DeleteNotificationCommand $command): void
    {
        $notification = $this->notificationRepository->find($command->notificationId);
        if ($notification === null) {
            throw new \DomainException("Notification not found: {$command->notificationId}");
        }

        $this->deleteNotification->execute($notification);
    }
}
