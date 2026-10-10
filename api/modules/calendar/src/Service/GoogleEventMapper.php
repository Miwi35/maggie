<?php

namespace Maggie\Calendar\Service;

use Google\Service\Calendar\Event as GoogleEvent;
use Google\Service\Calendar\EventDateTime;
use Google\Service\Calendar\EventReminder;
use Google\Service\Calendar\EventReminders;
use Maggie\Calendar\Entity\Agenda;
use Maggie\Calendar\Entity\Event;
use Maggie\Calendar\Enum\EventStatus;

class GoogleEventMapper
{
    public function fromGoogle(GoogleEvent $googleEvent, Agenda $agenda, ?Event $existing = null): Event
    {
        $event = $existing ?? new Event();
        $event->setAgenda($agenda);

        /** @var string $summary */
        $summary = $googleEvent->getSummary();
        $event->setSummary($summary ?: '(No title)');
        $event->setDescription($googleEvent->getDescription());
        $event->setLocation($googleEvent->getLocation());

        // Date/time handling
        /** @var ?EventDateTime $start */
        $start = $googleEvent->getStart();
        /** @var ?EventDateTime $end */
        $end = $googleEvent->getEnd();

        /** @var ?string $startDate */
        $startDate = $start?->getDate();
        /** @var ?string $startDateTime */
        $startDateTime = $start?->getDateTime();

        if ($startDate) {
            // All-day event: Google's `end.date` is the day after the last one,
            // ours is the last one (MAG-382) — the one conversion there is.
            /** @var ?string $endDate */
            $endDate = $end?->getDate();
            $first = self::day($startDate);
            $last = null !== $endDate && '' !== $endDate ? self::day($endDate)->modify('-1 day') : $first;
            $event->scheduleAllDay($first, $last < $first ? $first : $last);
            $event->setTimeZone($agenda->getTimeZone());
        } elseif ($startDateTime) {
            /** @var ?string $endDateTime */
            $endDateTime = $end?->getDateTime();
            $startAt = new \DateTimeImmutable($startDateTime);
            $event->scheduleTimed($startAt, $endDateTime ? new \DateTimeImmutable($endDateTime) : $startAt);
            /** @var ?string $tz */
            $tz = $start?->getTimeZone();
            if ($tz) {
                $event->setTimeZone($tz);
            }
        }

        // Recurrence
        $recurrence = $googleEvent->getRecurrence();
        if (!empty($recurrence)) {
            foreach ($recurrence as $rule) {
                if (str_starts_with($rule, 'RRULE:')) {
                    $event->setRrule(substr($rule, 6));
                    break;
                }
            }
        }

        // Status
        /** @var ?string $statusStr */
        $statusStr = $googleEvent->getStatus();
        if ($statusStr) {
            $status = EventStatus::tryFrom($statusStr);
            if (null !== $status) {
                $event->setStatus($status);
            }
        }

        // Reminders
        /** @var ?EventReminders $reminders */
        $reminders = $googleEvent->getReminders();
        if ($reminders) {
            $reminderData = ['useDefault' => $reminders->getUseDefault() ?: true];
            $overrides = $reminders->getOverrides();
            if (!empty($overrides)) {
                $reminderData['overrides'] = array_map(
                    fn (EventReminder $r) => [
                        'method' => $r->getMethod(),
                        'minutes' => $r->getMinutes(),
                    ],
                    $overrides
                );
            }
            $event->setReminders($reminderData);
        }

        // Google tracking fields
        $event->setGoogleEventId($googleEvent->getId());
        $event->setGoogleEtag($googleEvent->getEtag());
        /** @var ?string $updated */
        $updated = $googleEvent->getUpdated();
        if ($updated) {
            $event->setGoogleUpdatedAt(new \DateTimeImmutable($updated));
        }

        return $event;
    }

    /**
     * Build a partial GoogleEvent containing only the specified fields, for PATCH.
     *
     * @param string[] $changedFields
     */
    public function toGooglePatch(Event $event, array $changedFields): GoogleEvent
    {
        $googleEvent = new GoogleEvent();
        $fields = array_flip($changedFields);

        if (isset($fields['summary'])) {
            $googleEvent->setSummary($event->getSummary());
        }
        if (isset($fields['description'])) {
            $googleEvent->setDescription($event->getDescription());
        }
        if (isset($fields['location'])) {
            $googleEvent->setLocation($event->getLocation());
        }
        if (array_intersect_key($fields, array_flip(['startAt', 'endAt', 'allDay', 'startDate', 'endDate']))) {
            // Date fields are interdependent, always send both start+end together
            [$start, $end] = $this->scheduleToGoogle($event);
            $googleEvent->setStart($start);
            $googleEvent->setEnd($end);
        }
        if (isset($fields['rrule'])) {
            if (null !== $event->getRrule()) {
                $googleEvent->setRecurrence(['RRULE:'.$event->getRrule()]);
            } else {
                $googleEvent->setRecurrence([]);
            }
        }
        if (isset($fields['status'])) {
            $googleEvent->setStatus($event->getStatus()->value);
        }
        if (isset($fields['reminders'])) {
            $googleEvent->setReminders($this->remindersToGoogle($event->getReminders() ?? ['useDefault' => true]));
        }

        return $googleEvent;
    }

    public function toGoogle(Event $event): GoogleEvent
    {
        $googleEvent = new GoogleEvent();

        $googleEvent->setSummary($event->getSummary());
        $googleEvent->setDescription($event->getDescription());
        $googleEvent->setLocation($event->getLocation());

        // Date/time handling
        [$start, $end] = $this->scheduleToGoogle($event);
        $googleEvent->setStart($start);
        $googleEvent->setEnd($end);

        // Recurrence
        if (null !== $event->getRrule()) {
            $googleEvent->setRecurrence(['RRULE:'.$event->getRrule()]);
        }

        // Status
        $googleEvent->setStatus($event->getStatus()->value);

        // Reminders
        if (null !== $event->getReminders()) {
            $googleEvent->setReminders($this->remindersToGoogle($event->getReminders()));
        }

        return $googleEvent;
    }

    /**
     * The start and the end as Google takes them. An all-day event's `end.date`
     * is the day after its last one (MAG-382): `endDate + 1`, the inverse of
     * the `- 1` in fromGoogle().
     *
     * @return array{EventDateTime, EventDateTime}
     */
    private function scheduleToGoogle(Event $event): array
    {
        $start = new EventDateTime();
        $end = new EventDateTime();

        $startDate = $event->getStartDate();
        if (null !== $startDate) {
            $start->setDate($startDate->format('Y-m-d'));
            $end->setDate(($event->getEndDate() ?? $startDate)->modify('+1 day')->format('Y-m-d'));
        } else {
            $start->setDateTime($event->getStartInstant()->format(\DateTimeInterface::RFC3339));
            $start->setTimeZone($event->getTimeZone());
            $end->setDateTime($event->getEndInstant()->format(\DateTimeInterface::RFC3339));
            $end->setTimeZone($event->getTimeZone());
        }

        return [$start, $end];
    }

    /** A Google `date`, which is a day: no time, no zone. */
    private static function day(string $date): \DateTimeImmutable
    {
        return new \DateTimeImmutable(substr($date, 0, 10), new \DateTimeZone('UTC'));
    }

    /** @param array<string, mixed> $reminderData */
    private function remindersToGoogle(array $reminderData): EventReminders
    {
        $reminders = new EventReminders();
        $reminders->setUseDefault($reminderData['useDefault'] ?? true);
        if (isset($reminderData['overrides'])) {
            $overrides = array_map(function (array $o) {
                $r = new EventReminder();
                $r->setMethod($o['method']);
                $r->setMinutes($o['minutes']);

                return $r;
            }, $reminderData['overrides']);
            $reminders->setOverrides($overrides);
        }

        return $reminders;
    }
}
