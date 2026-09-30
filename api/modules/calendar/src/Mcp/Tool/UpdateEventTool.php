<?php

namespace Maggie\Calendar\Mcp\Tool;

use Maggie\Calendar\Entity\Event;
use Maggie\Calendar\Message\UpdateEventCommand;
use Mcp\Capability\Attribute\McpTool;
use Symfony\Component\Messenger\Exception\HandlerFailedException;
use Symfony\Component\Messenger\MessageBusInterface;
use Symfony\Component\Messenger\Stamp\HandledStamp;

#[McpTool(name: 'update_event', description: 'Update an existing calendar event. Only provided fields will be updated. Date format: YYYY-MM-DD. Time format: HH:MM. Duration in minutes. To empty an optional field, list its name in clear (description, location, rrule).')]
class UpdateEventTool
{
    public function __construct(
        private readonly MessageBusInterface $bus,
    ) {
    }

    /** @param list<string>|null $clear */
    public function __invoke(
        string $id,
        ?string $title = null,
        ?string $date = null,
        ?string $time = null,
        ?int $duration = null,
        ?string $description = null,
        ?string $location = null,
        ?array $clear = null,
    ): string {
        try {
            $startAt = null;
            $endAt = null;

            if (null !== $date || null !== $time) {
                $tz = new \DateTimeZone('Europe/Paris');
                $resolvedDate = $date ?? (new \DateTimeImmutable('now', $tz))->format('Y-m-d');
                $resolvedTime = $time ?? '00:00';
                $startAt = new \DateTimeImmutable("{$resolvedDate} {$resolvedTime}", $tz);

                if (null !== $duration) {
                    $endAt = $startAt->modify("+{$duration} minutes");
                }
            } elseif (null !== $duration) {
                // Duration change only — handler will compute from current startAt
                $endAt = null; // handled below
            }

            $envelope = $this->bus->dispatch(new UpdateEventCommand(
                eventId: $id,
                summary: $title,
                startAt: $startAt,
                endAt: $endAt,
                description: $description,
                location: $location,
                clearFields: array_values(array_intersect($clear ?? [], ['description', 'location', 'rrule'])),
            ));

            /** @var Event $event */
            $event = $envelope->last(HandledStamp::class)->getResult();

            return json_encode([
                'success' => true,
                'event' => [
                    'id' => (string) $event->getId(),
                    'summary' => $event->getSummary(),
                    'startAt' => $event->getStartAt()->format('c'),
                    'endAt' => $event->getEndAt()->format('c'),
                ],
            ], JSON_THROW_ON_ERROR);
        } catch (HandlerFailedException $e) {
            $cause = $e->getPrevious() ?? $e;

            return json_encode(['error' => $cause->getMessage()], JSON_THROW_ON_ERROR);
        }
    }
}
