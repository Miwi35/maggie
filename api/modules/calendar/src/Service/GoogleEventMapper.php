<?php

namespace Maggie\Calendar\Service;

use Google\Service\Calendar\Event as GoogleEvent;
use Google\Service\Calendar\EventDateTime;
use Google\Service\Calendar\EventReminder;
use Google\Service\Calendar\EventReminders;
use Maggie\Calendar\Entity\Agenda;
use Maggie\Calendar\Entity\Event;
use Maggie\Calendar\Entity\EventStatus;

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

        if ($start) {
            /** @var ?string $startDate */
            $startDate = $start->getDate();
            /** @var ?string $startDateTime */
            $startDateTime = $start->getDateTime();

            if ($startDate) {
                // All-day event
                $event->setAllDay(true);
                $event->setStartAt(new \DateTimeImmutable($startDate));
                $event->setTimeZone($agenda->getTimeZone());
            } elseif ($startDateTime) {
                $event->setAllDay(false);
                $event->setStartAt(new \DateTimeImmutable($startDateTime));
                /** @var ?string $tz */
                $tz = $start->getTimeZone();
                if ($tz) {
                    $event->setTimeZone($tz);
                }
            }
        }

        if ($end) {
            /** @var ?string $endDate */
            $endDate = $end->getDate();
            /** @var ?string $endDateTime */
            $endDateTime = $end->getDateTime();

            if ($endDate) {
                $event->setEndAt(new \DateTimeImmutable($endDate));
            } elseif ($endDateTime) {
                $event->setEndAt(new \DateTimeImmutable($endDateTime));
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
            if ($status !== null) {
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
                    fn(EventReminder $r) => [
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
        if (isset($fields['startAt']) || isset($fields['endAt']) || isset($fields['allDay'])) {
            // Date fields are interdependent, always send both start+end together
            $start = new EventDateTime();
            $end = new EventDateTime();

            if ($event->isAllDay()) {
                $start->setDate($event->getStartAt()->format('Y-m-d'));
                $end->setDate($event->getEndAt()->format('Y-m-d'));
            } else {
                $start->setDateTime($event->getStartAt()->format(\DateTimeInterface::RFC3339));
                $start->setTimeZone($event->getTimeZone());
                $end->setDateTime($event->getEndAt()->format(\DateTimeInterface::RFC3339));
                $end->setTimeZone($event->getTimeZone());
            }

            $googleEvent->setStart($start);
            $googleEvent->setEnd($end);
        }
        if (isset($fields['rrule'])) {
            if ($event->getRrule() !== null) {
                $googleEvent->setRecurrence(['RRULE:' . $event->getRrule()]);
            } else {
                $googleEvent->setRecurrence([]);
            }
        }
        if (isset($fields['status'])) {
            $googleEvent->setStatus($event->getStatus()->value);
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
        $start = new EventDateTime();
        $end = new EventDateTime();

        if ($event->isAllDay()) {
            $start->setDate($event->getStartAt()->format('Y-m-d'));
            $end->setDate($event->getEndAt()->format('Y-m-d'));
        } else {
            $start->setDateTime($event->getStartAt()->format(\DateTimeInterface::RFC3339));
            $start->setTimeZone($event->getTimeZone());
            $end->setDateTime($event->getEndAt()->format(\DateTimeInterface::RFC3339));
            $end->setTimeZone($event->getTimeZone());
        }

        $googleEvent->setStart($start);
        $googleEvent->setEnd($end);

        // Recurrence
        if ($event->getRrule() !== null) {
            $googleEvent->setRecurrence(['RRULE:' . $event->getRrule()]);
        }

        // Status
        $googleEvent->setStatus($event->getStatus()->value);

        // Reminders
        $reminderData = $event->getReminders();
        if ($reminderData !== null) {
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
            $googleEvent->setReminders($reminders);
        }

        return $googleEvent;
    }
}
