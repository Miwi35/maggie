<?php

declare(strict_types=1);

namespace Maggie\Notification\MessageHandler;

use Doctrine\ORM\EntityManagerInterface;
use Maggie\Notification\Message\ExpireNotificationsCommand;
use Maggie\Notification\Message\WithdrawPushNotificationCommand;
use Maggie\Notification\Repository\NotificationRepository;
use Symfony\Component\Clock\ClockInterface;
use Symfony\Component\Messenger\Attribute\AsMessageHandler;
use Symfony\Component\Messenger\MessageBusInterface;
use Symfony\Component\Messenger\Stamp\DispatchAfterCurrentBusStamp;

#[AsMessageHandler]
class ExpireNotificationsHandler
{
    public function __construct(
        private readonly NotificationRepository $notificationRepository,
        private readonly EntityManagerInterface $em,
        private readonly MessageBusInterface $bus,
        private readonly ClockInterface $clock,
    ) {
    }

    public function __invoke(ExpireNotificationsCommand $command): void
    {
        $now = $this->clock->now();
        $notifications = $this->notificationRepository->findLiveByRelatedEntity($command->relatedEntityIri, $now);
        if ([] === $notifications) {
            return;
        }

        foreach ($notifications as $notification) {
            $notification->setExpiresAt($now);
        }
        $this->em->flush();

        foreach ($notifications as $notification) {
            // A phone in a pocket has its app closed: only a push takes an unread alert down from its tray.
            if (null === $notification->getReadAt()) {
                $this->bus->dispatch(new WithdrawPushNotificationCommand((string) $notification->getId()), [new DispatchAfterCurrentBusStamp()]);
            }
        }
    }
}
