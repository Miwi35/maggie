<?php

declare(strict_types=1);

namespace Maggie\Calendar\Service;

use Maggie\Calendar\Entity\Event;

/**
 * Between a delay someone names — "une heure avant" — and what an event stores.
 *
 * Google's shape, `{useDefault, overrides: [{method, minutes}]}`, is the only one
 * `CheckRemindersCommand` reads, so no caller builds it by hand: a bare list
 * fires nothing and says nothing (MAG-121). The bounds `Event` carries as
 * validation attributes for the HTTP surface are enforced here for the MCP tools,
 * which persist straight through Doctrine and never meet the validator.
 */
final class EventReminders
{
    /**
     * Google's shape for a list of delays, or null for "no reminder at all".
     *
     * @param list<int> $minutes minutes before the start, as the caller said them
     *
     * @return array<string, mixed>|null
     *
     * @throws \DomainException on a delay nothing can honour — the MCP tools turn it into an error Maggie can read
     */
    public static function fromMinutes(array $minutes): ?array
    {
        $minutes = array_values(array_unique($minutes));

        if ([] === $minutes) {
            return null;
        }

        if (\count($minutes) > Event::MAX_REMINDERS) {
            throw new \DomainException(sprintf('An event takes at most %d reminders.', Event::MAX_REMINDERS));
        }

        foreach ($minutes as $delay) {
            // Zero is not "right now": the cron skips it, so accepting it would
            // promise a reminder that never comes.
            if ($delay < 1 || $delay > Event::MAX_REMINDER_MINUTES) {
                throw new \DomainException(sprintf('A reminder is between 1 and %d minutes before the start, got %d.', Event::MAX_REMINDER_MINUTES, $delay));
            }
        }

        sort($minutes);

        return [
            'useDefault' => false,
            'overrides' => array_map(
                static fn (int $delay) => ['method' => 'popup', 'minutes' => $delay],
                $minutes,
            ),
        ];
    }

    /**
     * The delays an event holds, so a tool can say back what it stored.
     *
     * Anything that is not a positive delay is left out rather than reported:
     * this reads events the Google import wrote, and the cron ignores those too.
     *
     * @param array<string, mixed>|null $reminders
     *
     * @return list<int>
     */
    public static function toMinutes(?array $reminders): array
    {
        $overrides = $reminders['overrides'] ?? [];
        if (!\is_array($overrides)) {
            return [];
        }

        $minutes = [];
        foreach ($overrides as $override) {
            $delay = \is_array($override) ? (int) ($override['minutes'] ?? 0) : 0;
            if ($delay > 0) {
                $minutes[] = $delay;
            }
        }

        sort($minutes);

        return $minutes;
    }
}
