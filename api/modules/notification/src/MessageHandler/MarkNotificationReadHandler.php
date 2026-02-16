<?php

declare(strict_types=1);

namespace Maggie\Notification\MessageHandler;

use Maggie\Notification\Entity\Notification;
use Maggie\Notification\Message\MarkNotificationReadCommand;
use Maggie\Notification\Repository\NotificationRepository;
use Maggie\Notification\UseCase\UpdateNotification;
use Symfony\Component\Messenger\Attribute\AsMessageHandler;

#[AsMessageHandler]
class MarkNotificationReadHandler
{
    public function __construct(
        private readonly NotificationRepository $notificationRepository,
        private readonly UpdateNotification $updateNotification,
    ) {
    }

    public function __invoke(MarkNotificationReadCommand $command): Notification
    {
        $notification = $this->notificationRepository->find($command->notificationId);
        if ($notification === null) {
            throw new \DomainException("Notification not found: {$command->notificationId}");
        }

        $notification->setReadAt($command->readAt ?? new \DateTimeImmutable());

        return $this->updateNotification->execute($notification);
    }
}
