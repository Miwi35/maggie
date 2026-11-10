<?php

declare(strict_types=1);

namespace Maggie\Health\Reminder;

use Maggie\Core\Reminder\ReminderReport;
use Maggie\Core\Reminder\ReminderSourceInterface;
use Maggie\Health\Entity\HealthProfile;
use Maggie\Health\Repository\HealthProfileRepository;
use Maggie\Health\Service\DailyEnergyReader;
use Maggie\Health\Service\FastingReader;
use Maggie\Notification\Enum\NotificationType;
use Maggie\Notification\Message\CreateNotificationCommand;
use Maggie\Notification\Repository\NotificationRepository;
use Psr\Log\LoggerInterface;
use Symfony\Component\DependencyInjection\Attribute\AsTaggedItem;
use Symfony\Component\Messenger\MessageBusInterface;

/**
 * « La fenêtre se ferme » (MAG-770): when the eating window of a user who
 * fasts ends, one push, encouraging and with no figure. Filed against
 * Santé › Nutrition with the window's end as its occurrence, so a window
 * never alerts twice; a cron that missed the minute catches up within
 * {@see self::LATE_MINUTES}, never later — a window closed hours ago is
 * no longer news.
 */
#[AsTaggedItem(priority: 5)]
final class FastingReminderSource implements ReminderSourceInterface
{
    public const LINK = '/health/nutrition';

    private const LATE_MINUTES = 30;

    public function __construct(
        private readonly HealthProfileRepository $profiles,
        private readonly NotificationRepository $notifications,
        private readonly MessageBusInterface $bus,
        private readonly LoggerInterface $logger,
    ) {
    }

    public function remind(\DateTimeImmutable $now): ReminderReport
    {
        $created = 0;
        $lines = [];
        $warnings = [];
        $local = $now->setTimezone(new \DateTimeZone(DailyEnergyReader::TIME_ZONE));

        foreach ($this->profiles->findFasting() as $profile) {
            try {
                // Yesterday's window may end after midnight.
                foreach ([$local->modify('-1 day'), $local] as $day) {
                    if ($this->alert($profile, $day->setTime(0, 0), $now)) {
                        ++$created;
                        $lines[] = \sprintf('  Eating window closed for %s', $profile->getUser()->getEmail());
                    }
                }
            } catch (\Throwable $e) {
                // One user's trouble must not cost the others their reminder.
                $this->logger->error('Fasting reminder failed for {user}: {error}', ['user' => (string) $profile->getUser()->getId(), 'error' => $e->getMessage(), 'exception' => $e]);
                $warnings[] = \sprintf('Fasting reminder failed for %s: %s', $profile->getUser()->getEmail(), $e->getMessage());
            }
        }

        return new ReminderReport($created, $lines, $warnings);
    }

    private function alert(HealthProfile $profile, \DateTimeImmutable $day, \DateTimeImmutable $now): bool
    {
        $end = FastingReader::window($profile, $day)?->end;
        if (null === $end || $now < $end || $now >= $end->modify('+'.self::LATE_MINUTES.' minutes')) {
            return false;
        }
        $user = $profile->getUser();
        if ($this->notifications->occurrenceExists($user, NotificationType::Health, self::LINK, $end)) {
            return false;
        }

        $this->bus->dispatch(new CreateNotificationCommand(
            type: NotificationType::Health->value,
            title: 'La fenêtre se ferme.',
            body: \sprintf('Bon jeûne jusqu\'à demain %s.', self::hour($profile->getFastingWindowStart())),
            relatedEntityIri: self::LINK,
            userId: (string) $user->getId(),
            occurrenceStartAt: $end,
        ));

        return true;
    }

    /** « 12 h », « 12 h 30 ». */
    private static function hour(string $time): string
    {
        [$hour, $minute] = explode(':', $time);

        return (int) $hour.' h'.('00' === $minute ? '' : ' '.$minute);
    }
}
