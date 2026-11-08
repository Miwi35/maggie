<?php

declare(strict_types=1);

namespace Maggie\Health\Service;

use Maggie\Core\Entity\User;
use Maggie\Health\Entity\HealthDay;
use Maggie\Health\Repository\HealthDayRepository;
use Psr\Clock\ClockInterface;

/**
 * The health journal (MAG-750) as the endpoint and the tools return it. The
 * journal keeps what was done; today's `balanceKcal` alone is the live
 * balance of {@see DailyBalanceReader}, planned meals included, like Santé ›
 * Nutrition shows it.
 */
final class HealthDayReader
{
    public const MAX_DAYS = 400;

    public function __construct(
        private readonly HealthDayRepository $days,
        private readonly DailyBalanceReader $balance,
        private readonly ClockInterface $clock,
    ) {
    }

    /**
     * The day's entry; a day not in the journal is an empty entry (`id` null).
     *
     * @return array<string, mixed>
     *
     * @throws \InvalidArgumentException naming the parameter, for the caller to refuse
     */
    public function day(User $user, string $date): array
    {
        $day = DailyEnergyReader::day('date', $date);
        $entry = $this->days->findOneByDate($user, $day)?->describe() ?? HealthDay::empty($date);

        return $this->live($user, $entry);
    }

    /**
     * The journal's days from $from to $to included, the oldest first; the
     * days not in the journal are left out.
     *
     * @return list<array<string, mixed>>
     *
     * @throws \InvalidArgumentException
     */
    public function range(User $user, string $from, string $to): array
    {
        $start = DailyEnergyReader::day('from', $from);
        $last = DailyEnergyReader::day('to', $to);
        if ($start > $last) {
            throw new \InvalidArgumentException('from must not be after to.');
        }
        if ($start->diff($last)->days >= self::MAX_DAYS) {
            throw new \InvalidArgumentException('The period is at most '.self::MAX_DAYS.' days.');
        }

        return array_map(
            fn (HealthDay $day): array => $this->live($user, $day->describe()),
            $this->days->findInRange($user, $start, $last),
        );
    }

    /**
     * @param array<string, mixed> $entry
     *
     * @return array<string, mixed>
     */
    private function live(User $user, array $entry): array
    {
        $today = $this->clock->now()->setTimezone(new \DateTimeZone(DailyEnergyReader::TIME_ZONE))->format('Y-m-d');
        if ($entry['date'] === $today) {
            $entry['balanceKcal'] = $this->balance->range($user, $today, $today)[0]['balanceKcal'];
        }

        return $entry;
    }
}
