<?php

declare(strict_types=1);

namespace Maggie\Calendar\Service;

use Maggie\Calendar\Repository\PeriodRepository;
use Maggie\Core\Entity\User;

/**
 * The context of a day (MAG-811): weekend, and every period covering it. The code knows no kind of
 * period: what « Garde » or « Férié » means is Maggie's to read in the name.
 */
class DayContext
{
    public function __construct(
        private readonly PeriodRepository $periods,
        private readonly PeriodRecurrence $recurrence,
    ) {
    }

    /** @param \DateTimeImmutable $day any instant of the day, read in its own zone */
    public function of(User $user, \DateTimeImmutable $day): DayContextView
    {
        $from = $day->setTime(0, 0);
        $to = $from->modify('+1 day');
        $covering = $this->recurrence->expandAll($this->periods->findCovering($user, $from, $to), $from, $to);

        return new DayContextView($from->format('Y-m-d'), (int) $from->format('N') >= 6, $covering);
    }
}
