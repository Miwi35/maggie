<?php

namespace Maggie\Agenda\Mcp\Tool;

use Maggie\Agenda\Entity\Event;
use Maggie\Agenda\Message\CreateEventCommand;
use Mcp\Capability\Attribute\McpTool;
use Symfony\Component\Messenger\Exception\HandlerFailedException;
use Symfony\Component\Messenger\MessageBusInterface;
use Symfony\Component\Messenger\Stamp\HandledStamp;

#[McpTool(name: 'create_event', description: 'Create a new calendar event. Date format: YYYY-MM-DD. Time format: HH:MM. Duration in minutes (default 60). Returns the created event.')]
class CreateEventTool
{
    public function __construct(
        private readonly MessageBusInterface $bus,
    ) {
    }

    public function __invoke(
        string $title,
        string $date,
        string $time = '09:00',
        int $duration = 60,
        ?string $description = null,
        ?string $location = null,
    ): string {
        $startAt = new \DateTimeImmutable("{$date} {$time}", new \DateTimeZone('Europe/Paris'));
        $endAt = $startAt->modify("+{$duration} minutes");

        try {
            $envelope = $this->bus->dispatch(new CreateEventCommand(
                summary: $title,
                startAt: $startAt,
                endAt: $endAt,
                description: $description,
                location: $location,
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
                    'calendar' => $event->getCalendar()->getName(),
                ],
            ], JSON_THROW_ON_ERROR);
        } catch (HandlerFailedException $e) {
            $cause = $e->getPrevious() ?? $e;
            return json_encode(['error' => $cause->getMessage()], JSON_THROW_ON_ERROR);
        }
    }
}
