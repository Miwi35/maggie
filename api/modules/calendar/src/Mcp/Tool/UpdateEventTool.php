<?php

namespace Maggie\Calendar\Mcp\Tool;

use Maggie\Calendar\Entity\Event;
use Maggie\Calendar\Message\UpdateEventCommand;
use Maggie\Calendar\Service\AgendaResolver;
use Maggie\Core\Mcp\McpUserContext;
use Maggie\Core\Mcp\MissingMcpUserException;
use Mcp\Capability\Attribute\McpTool;
use Symfony\Component\Messenger\Exception\HandlerFailedException;
use Symfony\Component\Messenger\MessageBusInterface;
use Symfony\Component\Messenger\Stamp\HandledStamp;

#[McpTool(name: 'update_event', description: 'Update an existing calendar event. Only provided fields will be updated. Date format: YYYY-MM-DD. Time format: HH:MM. Duration in minutes. To empty an optional field, list its name in clear (description, location, rrule). Use agenda_id to move the event to another agenda: pass its name as the user said it (e.g. "Concerts", case and accents do not matter) or its id — no need to call manage_agendas first. An unknown or ambiguous name returns an error listing the user\'s agendas.')]
class UpdateEventTool
{
    public function __construct(
        private readonly MessageBusInterface $bus,
        private readonly McpUserContext $userContext,
        private readonly AgendaResolver $agendaResolver,
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
        ?string $agenda_id = null,
    ): string {
        try {
            if (null !== $agenda_id) {
                $agenda_id = (string) $this->agendaResolver
                    ->resolve($this->userContext->requireUser(), $agenda_id)
                    ->getId();
            }

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
                agendaId: $agenda_id,
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
                    'agenda' => $event->getAgenda()->getName(),
                ],
            ], JSON_THROW_ON_ERROR);
        } catch (MissingMcpUserException|\DomainException $e) {
            return json_encode(['error' => $e->getMessage()], JSON_THROW_ON_ERROR);
        } catch (HandlerFailedException $e) {
            $cause = $e->getPrevious() ?? $e;

            return json_encode(['error' => $cause->getMessage()], JSON_THROW_ON_ERROR);
        }
    }
}
