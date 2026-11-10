<?php

declare(strict_types=1);

namespace Maggie\Health\Service;

use Maggie\Core\Entity\User;
use Maggie\Health\Dto\FastingWindow;
use Maggie\Health\Entity\FoodIntake;
use Maggie\Health\Entity\HealthProfile;
use Maggie\Health\Repository\FoodIntakeRepository;
use Maggie\Health\Repository\HealthProfileRepository;
use Psr\Clock\ClockInterface;

/**
 * A day's intermittent fast (MAG-770), read from the food journal: nothing is
 * started by hand. The eating window opens at the profile's start and lasts
 * the protocol's hours; the day's first intake opens it, the window's end
 * closes it.
 *
 * - `fasting`: no intake yet today and the window not over;
 * - `open`: the day's first intake is eaten and the window not over;
 * - `closed`: the window is over, the fast runs until tomorrow's.
 *
 * `lastIntakeAt` is the latest intake up to now, the day's or the day
 * before's: what the fast in progress is counted from. A past day is read as
 * at its end, a future day gives its window only. The day is local: Europe/Paris.
 */
final class FastingReader
{
    public function __construct(
        private readonly HealthProfileRepository $profiles,
        private readonly FoodIntakeRepository $intakes,
        private readonly ClockInterface $clock,
    ) {
    }

    /**
     * @return array{date: string, protocol: ?string, windowStart: ?string, windowEnd: ?string, openedAt: ?string, lastIntakeAt: ?string, state: ?string, nightFastMinutes: ?int}
     *
     * @throws \InvalidArgumentException naming the parameter, for the caller to refuse
     */
    public function day(User $user, string $date): array
    {
        $day = DailyEnergyReader::day('date', $date);
        $profile = $this->profiles->findOneByUser($user) ?? (new HealthProfile())->setUser($user);
        $now = $this->clock->now()->setTimezone($day->getTimezone());
        $end = $day->modify('+1 day');

        $protocol = $profile->getFastingProtocol();
        $window = self::window($profile, $day);
        $read = [
            'date' => $date,
            'protocol' => $protocol,
            'windowStart' => null === $window ? null : $profile->getFastingWindowStart(),
            'windowEnd' => $window?->end->format('H:i'),
            'openedAt' => null,
            'lastIntakeAt' => null,
            'state' => null,
            'nightFastMinutes' => null,
        ];
        if ($day > $now) {
            return $read;
        }

        $at = min($now, $end);
        $yesterday = $this->intakes->findInRange($user, $day->modify('-1 day'), $day->modify('-1 day'));
        $today = $this->intakes->findInRange($user, $day, $day);
        // An intake typed ahead of time has not been eaten yet.
        $eaten = static fn (FoodIntake $intake): bool => $intake->getEatenAt() <= $at;
        $todayEaten = array_values(array_filter($today, $eaten));
        $allEaten = array_values(array_filter([...$yesterday, ...$today], $eaten));
        $first = $todayEaten[0] ?? null;
        $last = [] === $allEaten ? null : $allEaten[array_key_last($allEaten)];

        $read['openedAt'] = $first?->getEatenAt()?->setTimezone($day->getTimezone())->format(\DATE_ATOM);
        $read['lastIntakeAt'] = $last?->getEatenAt()?->setTimezone($day->getTimezone())->format(\DATE_ATOM);
        $read['nightFastMinutes'] = self::nightFastMinutes($yesterday, $today);
        if (null !== $window) {
            $read['state'] = match (true) {
                $at >= $window->end => 'closed',
                null !== $first => 'open',
                default => 'fasting',
            };
        }

        return $read;
    }

    /**
     * The day's eating window, null when the user does not fast. It may end
     * after midnight: a window opening at 20:00 for 10 hours ends at 06:00 the
     * next day.
     */
    public static function window(HealthProfile $profile, \DateTimeImmutable $day): ?FastingWindow
    {
        $hours = $profile->getFastingWindowHours();
        if (null === $hours) {
            return null;
        }
        [$hour, $minute] = array_map('intval', explode(':', $profile->getFastingWindowStart()));
        $start = $day->setTime($hour, $minute);

        return new FastingWindow($start, $start->modify("+{$hours} hours"));
    }

    /**
     * The fast that ended that day: from the day before's last intake to the
     * day's first, in minutes; null when either day has none.
     *
     * @param list<FoodIntake> $dayBefore the day before's intakes, in the order eaten
     * @param list<FoodIntake> $day       the day's intakes, in the order eaten
     */
    public static function nightFastMinutes(array $dayBefore, array $day): ?int
    {
        $last = [] === $dayBefore ? null : $dayBefore[array_key_last($dayBefore)]->getEatenAt();
        $first = $day[0] ?? null;
        $first = $first?->getEatenAt();
        if (null === $last || null === $first || $first <= $last) {
            return null;
        }

        return intdiv($first->getTimestamp() - $last->getTimestamp(), 60);
    }
}
