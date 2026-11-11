<?php

declare(strict_types=1);

namespace Maggie\Health\Doctrine;

use Doctrine\Bundle\DoctrineBundle\Attribute\AsDoctrineListener;
use Doctrine\ORM\Events;

/**
 * First of the postFlush listeners: tells the planned sessions' listeners
 * that the flush they noted is committed (MAG-777, {@see DispatchesOnceCommittedTrait}).
 */
#[AsDoctrineListener(event: Events::postFlush, priority: 100)]
final class AgendaFlushCommitted
{
    public function __construct(
        private readonly PlannedWorkoutListener $plannedWorkouts,
        private readonly SportEventListener $sportEvents,
    ) {
    }

    public function postFlush(): void
    {
        $this->plannedWorkouts->committed();
        $this->sportEvents->committed();
    }
}
