<?php

declare(strict_types=1);

namespace Maggie\Health\Service;

use Maggie\Health\Entity\WorkoutMakeup;
use Maggie\Health\Enum\MakeupOption;
use Maggie\Notification\Enum\NotificationType;
use Maggie\Notification\Message\CreateNotificationCommand;
use Maggie\Notification\Message\MarkNotificationReadCommand;
use Maggie\Notification\Message\WithdrawPushNotificationCommand;
use Maggie\Notification\Repository\NotificationRepository;
use Symfony\Component\Messenger\MessageBusInterface;
use Symfony\Component\Messenger\Stamp\DispatchAfterCurrentBusStamp;

/**
 * The interruption of a missed session (MAG-771): « Séance de 18 h 30
 * manquée, monsieur. » then the make-ups as buttons — said once, without
 * reproach; read and taken off the phone once answered or overtaken.
 */
final class WorkoutMakeupNotifications
{
    public function __construct(
        private readonly MakeupSchedule $schedule,
        private readonly NotificationRepository $notifications,
        private readonly MessageBusInterface $bus,
    ) {
    }

    public function propose(WorkoutMakeup $makeup, \DateTimeImmutable $now): void
    {
        $options = $this->schedule->options($makeup, $now);
        $when = [MakeupOption::Tomorrow] === $options ? 'demain' : 'ce soir';

        $this->bus->dispatch(new CreateNotificationCommand(
            type: NotificationType::WorkoutMakeup->value,
            title: \sprintf('Séance de %s manquée, monsieur.', self::hour($makeup->getMissedAt())),
            body: $makeup->getStreak() > 0
                ? \sprintf('Pour garder votre série de %d, %s :', $makeup->getStreak(), $when)
                : \sprintf('Pour la rattraper, %s :', $when),
            relatedEntityIri: WorkoutMakeup::iri($makeup),
            userId: (string) $makeup->getUser()->getId(),
            actions: array_map(static fn (MakeupOption $option): string => $option->value, $options),
        ));
    }

    /** Seen, answered or overtaken: read here, and taken off every phone. */
    public function withdraw(WorkoutMakeup $makeup, \DateTimeImmutable $now): void
    {
        foreach ($this->notifications->findUnreadByTypeAndEntity($makeup->getUser(), NotificationType::WorkoutMakeup, WorkoutMakeup::iri($makeup)) as $notification) {
            $this->bus->dispatch(new MarkNotificationReadCommand((string) $notification->getId(), $now));
            // A phone in a pocket has its app closed: only a push takes the notification down there.
            $this->bus->dispatch(new WithdrawPushNotificationCommand((string) $notification->getId()), [new DispatchAfterCurrentBusStamp()]);
        }
    }

    /** « 18 h », « 18 h 30 », in the owner's zone. */
    private static function hour(\DateTimeImmutable $at): string
    {
        $local = $at->setTimezone(new \DateTimeZone(DailyEnergyReader::TIME_ZONE));

        return $local->format('G').' h'.('00' === $local->format('i') ? '' : ' '.$local->format('i'));
    }
}
