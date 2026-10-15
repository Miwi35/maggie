<?php

declare(strict_types=1);

namespace Maggie\Calendar\EventSubscriber;

use Maggie\Calendar\Event\TaskCompleted;
use Maggie\Calendar\Event\TaskReopened;
use Maggie\Calendar\Repository\DayPlanRepository;
use Maggie\Calendar\Service\TaskOverdue;
use Maggie\Core\Projection\WorkCollector;
use Maggie\Core\Repository\UserRepository;
use Symfony\Component\Messenger\Attribute\AsMessageHandler;

/**
 * A task ticked or unticked moves « Maintenant » and the ring of every day
 * from today on that holds it (MAG-497): those days are published again, for
 * the screens and widgets that only listen to the day.
 */
#[AsMessageHandler(bus: 'event.bus')]
final class PublishDayPlansOnTaskTransition
{
    public function __construct(
        private readonly DayPlanRepository $dayPlans,
        private readonly UserRepository $users,
        private readonly TaskOverdue $overdue,
        private readonly WorkCollector $collector,
    ) {
    }

    public function __invoke(TaskCompleted|TaskReopened $event): void
    {
        $user = $this->users->find($event->userId);
        if (null === $user) {
            return;
        }

        foreach ($this->dayPlans->findFromHoldingTask($user, $this->overdue->today($user), $event->taskId) as $plan) {
            $this->collector->touched($plan, ['slots']);
        }
    }
}
