<?php

declare(strict_types=1);

namespace Maggie\Calendar\Service;

use Maggie\Calendar\Entity\Task;
use Maggie\Calendar\Enum\TaskAlertReason;
use Maggie\Calendar\Enum\TaskUrgencyLevel;

/**
 * One task to alert on a morning, and why (MAG-407). `days` is the n of the
 * reason: days before the due day, or days late; 0 on the due day.
 */
final readonly class TaskAlert
{
    public function __construct(
        public Task $task,
        public TaskAlertReason $reason,
        public int $days,
        public TaskUrgencyLevel $urgency,
    ) {
    }
}
