<?php

namespace Maggie\Calendar\Mcp\Tool;

use Maggie\Calendar\Entity\Event;
use Maggie\Calendar\Message\CreateEventCommand;
use Maggie\Calendar\Service\AgendaResolver;
use Maggie\Core\Mcp\McpUserContext;
use Maggie\Core\Mcp\MissingMcpUserException;
use Mcp\Capability\Attribute\McpTool;
use Symfony\Component\Messenger\Exception\HandlerFailedException;
use Symfony\Component\Messenger\MessageBusInterface;
use Symfony\Component\Messenger\Stamp\HandledStamp;

#[McpTool(name: 'create_event', description: 'Create a new calendar event. Date format: YYYY-MM-DD. Time format: HH:MM. Duration in minutes (default 60). Use agenda_id to target a specific agenda: pass its name as the user said it (e.g. "Concerts", case and accents do not matter) or its id — no need to call manage_agendas first. An unknown or ambiguous name returns an error listing the user\'s agendas. Omit agenda_id for the default agenda — when the user has none the tool returns an error: ask which agenda to use rather than picking one.')]
class CreateEventTool
{
    public function __construct(
        private readonly MessageBusInterface $bus,
        private readonly McpUserContext $userContext,
        private readonly AgendaResolver $agendaResolver,
    ) {
    }

    public function __invoke(
        string $title,
        string $date,
        string $time = '09:00',
        int $duration = 60,
        ?string $description = null,
        ?string $location = null,
        ?string $agenda_id = null,
    ): string {
        $startAt = new \DateTimeImmutable("{$date} {$time}", new \DateTimeZone('Europe/Paris'));
        $endAt = $startAt->modify("+{$duration} minutes");
        $user = $this->userContext->getUser();

        try {
            if (null !== $agenda_id) {
                $agenda_id = (string) $this->agendaResolver
                    ->resolve($user ?? throw new MissingMcpUserException(), $agenda_id)
                    ->getId();
            }

            $envelope = $this->bus->dispatch(new CreateEventCommand(
                summary: $title,
                startAt: $startAt,
                endAt: $endAt,
                agendaId: $agenda_id,
                description: $description,
                location: $location,
                userId: null !== $user ? (string) $user->getId() : null,
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
