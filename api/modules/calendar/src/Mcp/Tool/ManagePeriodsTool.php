<?php

declare(strict_types=1);

namespace Maggie\Calendar\Mcp\Tool;

use Maggie\Calendar\Entity\Event;
use Maggie\Calendar\Entity\Period;
use Maggie\Calendar\Mcp\EventSchedule;
use Maggie\Calendar\Mcp\PeriodJson;
use Maggie\Calendar\Message\CreatePeriodCommand;
use Maggie\Calendar\Message\DeletePeriodCommand;
use Maggie\Calendar\Message\UpdatePeriodCommand;
use Maggie\Calendar\Repository\PeriodRepository;
use Maggie\Calendar\Service\AgendaResolver;
use Maggie\Calendar\Service\PeriodRecurrence;
use Maggie\Core\Mcp\McpUserContext;
use Maggie\Core\Mcp\MissingMcpUserException;
use Mcp\Capability\Attribute\McpTool;
use Symfony\Component\Messenger\Exception\HandlerFailedException;
use Symfony\Component\Messenger\MessageBusInterface;
use Symfony\Component\Messenger\Stamp\HandledStamp;

/**
 * Never completes a request by deduction: a partial one is refused with
 * `current`, what was given or what the period is, so the next call can be
 * whole.
 */
#[McpTool(name: 'manage_periods', description: 'A period links a context to a stretch of time (a place, a situation, custody, holidays, a trip, a public holiday): it records the fact and Maggie reads it to adapt her answers, reminders and proactions over that stretch. It sits beside the events, not a long event: events are planned inside it and it never conflicts with them. A context during which other things happen and get planned (a trip, holidays, custody, a stay away) is a period; what fills the whole time and makes the owner unavailable (a festival, a seminar, a trade fair) is an event, even over several days. A state of health is not a period. One period per stretch, never one event per day. After create, read the stretch with get_events and say which planned events it affects. Never synced with Google. Fields: name, agenda_id (id or name), schedule given whole: start_date (YYYY-MM-DD) + start_time (HH:MM) + end_date + end_time, or all_day true + start_date + end_date (EXCLUDED), rrule: RFC 5545 without the RRULE: prefix, e.g. FREQ=WEEKLY;INTERVAL=2. list: from, to (YYYY-MM-DD, to included) returns periods meeting those days, repeats unfolded; else every period as stored. create: name, agenda_id, schedule. update: period_id, only given fields (rrule "" stops repeating). delete: period_id, removes the series. Refused: error plus current. Returns what was saved in the period\'s zone, with startWeekday, endWeekday or lastDayWeekday, and lastDay for days.')]
class ManagePeriodsTool
{
    public function __construct(
        private readonly MessageBusInterface $bus,
        private readonly PeriodRepository $periodRepository,
        private readonly PeriodRecurrence $recurrence,
        private readonly AgendaResolver $agendaResolver,
        private readonly McpUserContext $userContext,
    ) {
    }

    public function __invoke(
        string $action,
        ?string $period_id = null,
        ?string $name = null,
        ?string $agenda_id = null,
        ?string $start_date = null,
        ?string $start_time = null,
        ?string $end_date = null,
        ?string $end_time = null,
        ?bool $all_day = null,
        ?string $rrule = null,
        ?string $from = null,
        ?string $to = null,
    ): string {
        $schedule = compact('start_date', 'start_time', 'end_date', 'end_time', 'all_day');

        try {
            return match ($action) {
                'list' => $this->list($from, $to),
                'create' => $this->create($name, $agenda_id, $schedule, $rrule),
                'update' => $this->update($period_id, $name, $agenda_id, $schedule, $rrule),
                'delete' => $this->delete($period_id),
                default => self::json(['error' => "Unknown action: {$action}. Use list, create, update, or delete."]),
            };
        } catch (MissingMcpUserException $e) {
            return self::json(['error' => $e->getMessage()]);
        } catch (HandlerFailedException $e) {
            return self::json(['error' => ($e->getPrevious() ?? $e)->getMessage()]);
        }
    }

    private function list(?string $from, ?string $to): string
    {
        $user = $this->userContext->requireUser();

        if (null === $from && null === $to) {
            $periods = $this->periodRepository->findByUser($user);

            return self::json(['periods' => array_map(PeriodJson::of(...), $periods), 'count' => \count($periods)]);
        }

        $given = self::given(compact('from', 'to'));
        $zone = new \DateTimeZone(Event::FALLBACK_TIME_ZONE);
        $first = null === $from ? false : \DateTimeImmutable::createFromFormat('!Y-m-d', $from, $zone);
        $last = null === $to ? false : \DateTimeImmutable::createFromFormat('!Y-m-d', $to, $zone);
        if (false === $first || false === $last || $first->format('Y-m-d') !== $from || $last->format('Y-m-d') !== $to) {
            return self::json(['error' => 'list reads from and to together, both YYYY-MM-DD (to included), or neither for every period as stored. Ask the user, never assume.', 'current' => $given]);
        }
        if ($last < $first) {
            return self::json(['error' => "to must not be before from, got {$from} → {$to}.", 'current' => $given]);
        }

        $until = $last->modify('+1 day');
        $periods = $this->recurrence->expandAll($this->periodRepository->findCovering($user, $first, $until), $first, $until);

        return self::json(['from' => $from, 'to' => $to, 'periods' => array_map(PeriodJson::of(...), $periods), 'count' => \count($periods)]);
    }

    /** @param array{start_date: ?string, start_time: ?string, end_date: ?string, end_time: ?string, all_day: ?bool} $schedule */
    private function create(?string $name, ?string $agendaId, array $schedule, ?string $rrule): string
    {
        $user = $this->userContext->requireUser();
        $given = self::given(compact('name') + ['agenda_id' => $agendaId] + $schedule + compact('rrule'));

        $missing = array_keys(array_filter(['name' => $name, 'agenda_id' => $agendaId], static fn (?string $value): bool => null === $value || '' === trim($value)));
        if ([] !== $missing) {
            return self::json(['error' => 'Missing for create: '.implode(', ', $missing).'. Ask the user, never assume.', 'current' => $given]);
        }

        try {
            $parsed = EventSchedule::required($schedule['start_date'], $schedule['start_time'], $schedule['end_date'], $schedule['end_time'], $schedule['all_day'], new \DateTimeZone(Event::FALLBACK_TIME_ZONE));
            $agenda = $this->agendaResolver->resolve($user, (string) $agendaId);

            /** @var Period $period */
            $period = $this->bus->dispatch(new CreatePeriodCommand(
                userId: (string) $user->getId(),
                agendaId: (string) $agenda->getId(),
                name: (string) $name,
                allDay: $parsed->allDay,
                startAt: $parsed->startAt,
                endAt: $parsed->endAt,
                startDate: $parsed->startDate,
                endDate: $parsed->endDate,
                rrule: '' === $rrule ? null : $rrule,
            ))->last(HandledStamp::class)?->getResult();
        } catch (\DomainException $e) {
            return self::json(['error' => $e->getMessage(), 'current' => $given]);
        } catch (HandlerFailedException $e) {
            return self::json(['error' => ($e->getPrevious() ?? $e)->getMessage(), 'current' => $given]);
        }

        return self::json(['success' => true, 'period' => PeriodJson::of($period)]);
    }

    /** @param array{start_date: ?string, start_time: ?string, end_date: ?string, end_time: ?string, all_day: ?bool} $schedule */
    private function update(?string $periodId, ?string $name, ?string $agendaId, array $schedule, ?string $rrule): string
    {
        $user = $this->userContext->requireUser();
        if (null === $periodId) {
            return self::json(['error' => 'period_id is required for update: its id comes from list.']);
        }

        $before = $this->periodRepository->findOneOfUser($user, $periodId);
        if (null === $before) {
            return self::json(['error' => "Period not found: {$periodId}"]);
        }
        // Read before the handler touches the entity: the state it was refused against.
        $current = PeriodJson::of($before);

        try {
            if (null !== $name && '' === trim($name)) {
                throw new \DomainException('name cannot be empty: give the new name, or leave it out to keep it.');
            }
            $parsed = EventSchedule::fromParts($schedule['start_date'], $schedule['start_time'], $schedule['end_date'], $schedule['end_time'], $schedule['all_day'], $before->zone());
            $agenda = null === $agendaId ? null : $this->agendaResolver->resolve($user, $agendaId);

            /** @var Period $period */
            $period = $this->bus->dispatch(new UpdatePeriodCommand(
                userId: (string) $user->getId(),
                periodId: $periodId,
                name: $name,
                agendaId: null === $agenda ? null : (string) $agenda->getId(),
                allDay: $parsed?->allDay,
                startAt: $parsed?->startAt,
                endAt: $parsed?->endAt,
                startDate: $parsed?->startDate,
                endDate: $parsed?->endDate,
                rrule: '' === $rrule ? null : $rrule,
                clearFields: '' === $rrule ? ['rrule'] : [],
            ))->last(HandledStamp::class)?->getResult();
        } catch (\DomainException $e) {
            return self::json(['error' => $e->getMessage(), 'current' => $current]);
        } catch (HandlerFailedException $e) {
            return self::json(['error' => ($e->getPrevious() ?? $e)->getMessage(), 'current' => $current]);
        }

        return self::json(['success' => true, 'period' => PeriodJson::of($period)]);
    }

    private function delete(?string $periodId): string
    {
        $user = $this->userContext->requireUser();
        if (null === $periodId) {
            return self::json(['error' => 'period_id is required for delete: its id comes from list.']);
        }

        $this->bus->dispatch(new DeletePeriodCommand(userId: (string) $user->getId(), periodId: $periodId));

        return self::json(['success' => true]);
    }

    /**
     * @param array<string, mixed> $values
     *
     * @return array<string, mixed>
     */
    private static function given(array $values): array
    {
        return array_filter($values, static fn (mixed $value): bool => null !== $value);
    }

    /** @param array<string, mixed> $data */
    private static function json(array $data): string
    {
        return json_encode($data, \JSON_THROW_ON_ERROR);
    }
}
