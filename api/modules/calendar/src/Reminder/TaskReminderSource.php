<?php

declare(strict_types=1);

namespace Maggie\Calendar\Reminder;

use Maggie\Calendar\Service\TaskReminders;
use Maggie\Core\Reminder\ReminderReport;
use Maggie\Core\Reminder\ReminderSourceInterface;
use Maggie\Core\Repository\UserRepository;
use Psr\Log\LoggerInterface;
use Symfony\Component\DependencyInjection\Attribute\AsTaggedItem;

/**
 * The task reminders of every user (MAG-510): the morning alerts of MAG-410 and
 * the nudges on the task « now », under the daily cap and the quiet hours
 * ({@see TaskReminders}).
 */
#[AsTaggedItem(priority: 20)]
final class TaskReminderSource implements ReminderSourceInterface
{
    public function __construct(
        private readonly TaskReminders $taskReminders,
        private readonly UserRepository $userRepository,
        private readonly LoggerInterface $logger,
    ) {
    }

    public function remind(\DateTimeImmutable $now): ReminderReport
    {
        $created = 0;
        $lines = [];
        $warnings = [];

        foreach ($this->userRepository->findAll() as $user) {
            try {
                $sent = $this->taskReminders->run($user);
            } catch (\Throwable $e) {
                // One user's trouble must not cost the others their reminders.
                $this->logger->error('Task reminders failed for {user}: {error}', ['user' => (string) $user->getId(), 'error' => $e->getMessage(), 'exception' => $e]);
                $warnings[] = \sprintf('Task reminders failed for %s: %s', $user->getEmail(), $e->getMessage());
                continue;
            }
            if ($sent > 0) {
                $lines[] = \sprintf('  Sent %d task reminder(s) to %s', $sent, $user->getEmail());
            }
            $created += $sent;
        }

        return new ReminderReport($created, $lines, $warnings);
    }
}
