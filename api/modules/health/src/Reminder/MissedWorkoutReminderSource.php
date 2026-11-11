<?php

declare(strict_types=1);

namespace Maggie\Health\Reminder;

use Maggie\Core\Reminder\ReminderReport;
use Maggie\Core\Reminder\ReminderSourceInterface;
use Maggie\Health\Entity\Workout;
use Maggie\Health\Event\PlannedWorkoutMissed;
use Maggie\Health\Repository\WorkoutRepository;
use Maggie\Health\Specification\IsPlannedWorkoutMissed;
use Psr\Log\LoggerInterface;
use Symfony\Component\DependencyInjection\Attribute\Autowire;
use Symfony\Component\Messenger\MessageBusInterface;

/**
 * « Séance manquée » (MAG-771): a planned session nothing kept, half an hour
 * after its end, raises {@see PlannedWorkoutMissed}; the use case proposes the
 * make-up once. A cron that missed the minute catches up within
 * {@see self::LATE_HOURS}, never later — a session missed days ago, or before
 * this rule existed, is no longer news.
 */
final class MissedWorkoutReminderSource implements ReminderSourceInterface
{
    public const LATE_HOURS = 12;

    public function __construct(
        private readonly WorkoutRepository $workouts,
        #[Autowire(service: 'event.bus')]
        private readonly MessageBusInterface $eventBus,
        private readonly LoggerInterface $logger,
    ) {
    }

    public function remind(\DateTimeImmutable $now): ReminderReport
    {
        $created = 0;
        $lines = [];
        $warnings = [];
        // A session ends at most a day after it starts.
        $from = $now->modify(\sprintf('-%d minutes -%d hours', Workout::MAX_DURATION_MINUTES + IsPlannedWorkoutMissed::GRACE_MINUTES, self::LATE_HOURS));
        $missed = new IsPlannedWorkoutMissed($now, false);

        foreach ($this->workouts->findMissCandidates($from, $now) as $planned) {
            $due = $planned->endsAt()->modify(\sprintf('+%d minutes', IsPlannedWorkoutMissed::GRACE_MINUTES));
            if (!$missed->isSatisfiedBy($planned) || $now >= $due->modify(\sprintf('+%d hours', self::LATE_HOURS))) {
                continue;
            }

            try {
                $this->eventBus->dispatch(new PlannedWorkoutMissed((string) $planned->getId()));
                ++$created;
                $lines[] = \sprintf('  Missed workout %s for %s', $planned->getTitle(), $planned->getUser()->getEmail());
            } catch (\Throwable $e) {
                // One user's trouble must not cost the others their reminder.
                $this->logger->error('Missed workout {workout} could not be proposed a make-up: {error}', ['workout' => (string) $planned->getId(), 'error' => $e->getMessage(), 'exception' => $e]);
                $warnings[] = \sprintf('Missed workout %s failed: %s', (string) $planned->getId(), $e->getMessage());
            }
        }

        return new ReminderReport($created, $lines, $warnings);
    }
}
