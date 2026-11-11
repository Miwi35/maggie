<?php

declare(strict_types=1);

namespace Maggie\Health\Service;

use Maggie\Health\Entity\WorkoutMakeup;
use Maggie\Health\Enum\MakeupOption;

/**
 * When a make-up takes place (MAG-771), in the owner's zone: softer and
 * shorter tonight at the next quarter hour, only if they can end by
 * {@see self::LAST_END}; tomorrow at the missed session's hour.
 */
final class MakeupSchedule
{
    public const LAST_END = '23:00';

    public function start(WorkoutMakeup $makeup, MakeupOption $option, \DateTimeImmutable $now): \DateTimeImmutable
    {
        $zone = new \DateTimeZone(DailyEnergyReader::TIME_ZONE);
        if (MakeupOption::Tomorrow === $option) {
            return $makeup->getMissed()->getStartedAt()->setTimezone($zone)->modify('+1 day');
        }

        $local = $now->setTimezone($zone);
        $minutes = (int) $local->format('G') * 60 + (int) $local->format('i') + ('00' === $local->format('s') ? 0 : 1);

        return $local->setTime(0, 0)->modify(\sprintf('+%d minutes', (int) ceil($minutes / 15) * 15));
    }

    /** @return list<MakeupOption> what the notification offers, in order: tomorrow always */
    public function options(WorkoutMakeup $makeup, \DateTimeImmutable $now): array
    {
        $local = $now->setTimezone(new \DateTimeZone(DailyEnergyReader::TIME_ZONE));
        [$hour, $minute] = explode(':', self::LAST_END);
        $lastEnd = $local->setTime((int) $hour, (int) $minute);

        $options = [];
        foreach ([MakeupOption::Softer, MakeupOption::Shorter] as $option) {
            $end = $this->start($makeup, $option, $now)->modify(\sprintf('+%d minutes', $makeup->minutesFor($option)));
            if ($end <= $lastEnd) {
                $options[] = $option;
            }
        }
        $options[] = MakeupOption::Tomorrow;

        return $options;
    }
}
