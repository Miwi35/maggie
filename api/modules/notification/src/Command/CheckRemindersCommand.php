<?php

declare(strict_types=1);

namespace Maggie\Notification\Command;

use Maggie\Calendar\Repository\EventRepository;
use Maggie\Notification\Message\CreateNotificationCommand;
use Maggie\Notification\Repository\NotificationRepository;
use Symfony\Component\Console\Attribute\AsCommand;
use Symfony\Component\Console\Command\Command;
use Symfony\Component\Console\Input\InputInterface;
use Symfony\Component\Console\Output\OutputInterface;
use Symfony\Component\Console\Style\SymfonyStyle;
use Symfony\Component\Messenger\MessageBusInterface;

#[AsCommand(
    name: 'maggie:notification:check-reminders',
    description: 'Check for due event reminders and create notifications',
)]
class CheckRemindersCommand extends Command
{
    public function __construct(
        private readonly EventRepository $eventRepository,
        private readonly NotificationRepository $notificationRepository,
        private readonly MessageBusInterface $messageBus,
    ) {
        parent::__construct();
    }

    protected function execute(InputInterface $input, OutputInterface $output): int
    {
        $io = new SymfonyStyle($input, $output);
        $now = new \DateTimeImmutable();
        $horizon = $now->modify('+24 hours');

        // Find events in the next 24h that have reminders
        $events = $this->eventRepository->findByDateRangeForAllUsers($now, $horizon);
        $created = 0;

        foreach ($events as $event) {
            $reminders = $event->getReminders();
            if ($reminders === null) {
                continue;
            }

            $overrides = $reminders['overrides'] ?? [];
            if (empty($overrides)) {
                continue;
            }

            $eventIri = '/api/events/' . $event->getId();

            foreach ($overrides as $override) {
                $minutes = (int) ($override['minutes'] ?? 0);
                if ($minutes <= 0) {
                    continue;
                }

                $triggerTime = $event->getStartAt()->modify("-{$minutes} minutes");

                if ($triggerTime > $now) {
                    continue;
                }

                // Check if notification already exists for this event + minutes combo
                if ($this->notificationRepository->reminderExists($eventIri, $minutes)) {
                    continue;
                }

                $userId = (string) $event->getAgenda()->getUser()->getId();

                $this->messageBus->dispatch(new CreateNotificationCommand(
                    type: 'reminder',
                    title: $event->getSummary(),
                    body: (string) $minutes,
                    relatedEntityIri: $eventIri,
                    userId: $userId,
                ));

                $created++;
                $io->writeln(sprintf(
                    '  Created reminder for "%s" (%d min before)',
                    $event->getSummary(),
                    $minutes,
                ));
            }
        }

        if ($created === 0) {
            $io->info('No due reminders found.');
        } else {
            $io->success(sprintf('Created %d reminder notification(s).', $created));
        }

        return Command::SUCCESS;
    }
}
