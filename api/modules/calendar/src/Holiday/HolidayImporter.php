<?php

declare(strict_types=1);

namespace Maggie\Calendar\Holiday;

use Maggie\Calendar\Entity\Agenda;
use Maggie\Calendar\Enum\PeriodHoliday;
use Maggie\Calendar\Message\CreatePeriodCommand;
use Maggie\Calendar\Message\UpdatePeriodCommand;
use Maggie\Calendar\Repository\PeriodRepository;
use Maggie\Calendar\Service\ModuleAgendas;
use Maggie\Core\Entity\User;
use Symfony\Component\Messenger\MessageBusInterface;

/**
 * Files a year of public holidays in each user's « Fériés » agenda (MAG-810):
 * one all-day period a day, `holiday` saying whose — France's (the son's
 * school), Geneva's (the owner's work) or both.
 *
 * A day that already has a holiday period there is neither created again nor
 * renamed, as the owner may have edited it: only its `holiday` is corrected.
 */
class HolidayImporter
{
    public const AGENDA_NAME = 'Fériés';
    public const AGENDA_COLOR = '#B39DDB';

    public function __construct(
        private readonly ModuleAgendas $moduleAgendas,
        private readonly PeriodRepository $periodRepository,
        private readonly MessageBusInterface $bus,
    ) {
    }

    /**
     * One period a day: a day on both sides is one `both` period named after
     * France, a day of Geneva alone takes the name of its table.
     *
     * @param array<string, string>|null $france the day to its name; null when France could not be read
     * @param array<string, string>      $geneva
     *
     * @return array<string, array{name: string, holiday: PeriodHoliday}> by day
     */
    public static function merge(?array $france, array $geneva): array
    {
        $days = [];
        foreach ($france ?? [] as $day => $name) {
            $days[$day] = ['name' => $name, 'holiday' => isset($geneva[$day]) ? PeriodHoliday::Both : PeriodHoliday::France];
        }
        foreach ($geneva as $day => $name) {
            $days[$day] ??= ['name' => $name, 'holiday' => PeriodHoliday::Geneve];
        }
        ksort($days);

        return $days;
    }

    /**
     * @param array<string, array{name: string, holiday: PeriodHoliday}> $days     what {@see merge()} gives
     * @param bool                                                       $complete false when France could not be read: the
     *                                                                             missing days are created, but no `holiday` is
     *                                                                             corrected — a `both` must not fall back to `geneve`
     *
     * @return array{created: int, updated: int}
     */
    public function importFor(User $user, array $days, bool $complete = true): array
    {
        if ([] === $days) {
            return ['created' => 0, 'updated' => 0];
        }

        // A new agenda is persisted, not flushed: the first period's command flushes it, so it is
        // indexed and published with it — a flush outside a message announces nothing.
        [$agenda] = $this->moduleAgendas->forUser($user, Agenda::MODULE_HOLIDAYS, self::AGENDA_NAME, self::AGENDA_COLOR, adoptLegacy: false);

        $existing = [];
        foreach ($this->periodRepository->findHolidaysIn($agenda, (string) array_key_first($days), (string) array_key_last($days)) as $period) {
            $existing[$period->getStartDate()?->format('Y-m-d')] ??= $period;
        }

        $userId = (string) $user->getId();
        $created = 0;
        $updated = 0;
        foreach ($days as $day => ['name' => $name, 'holiday' => $holiday]) {
            $period = $existing[$day] ?? null;
            if (null === $period) {
                $start = new \DateTimeImmutable($day);
                $this->bus->dispatch(new CreatePeriodCommand(
                    userId: $userId,
                    agendaId: (string) $agenda->getId(),
                    name: $name,
                    allDay: true,
                    startDate: $start,
                    endDate: $start->modify('+1 day'),
                    timeZone: $agenda->getTimeZone(),
                    holiday: $holiday->value,
                ));
                ++$created;
            } elseif ($complete && $period->getHoliday() !== $holiday) {
                $this->bus->dispatch(new UpdatePeriodCommand(userId: $userId, periodId: (string) $period->getId(), holiday: $holiday->value));
                ++$updated;
            }
        }

        return ['created' => $created, 'updated' => $updated];
    }
}
