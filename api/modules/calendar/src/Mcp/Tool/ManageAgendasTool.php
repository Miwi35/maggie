<?php

declare(strict_types=1);

namespace Maggie\Calendar\Mcp\Tool;

use Maggie\Calendar\Entity\Agenda;
use Maggie\Calendar\Message\CreateAgendaCommand;
use Maggie\Calendar\Message\DeleteAgendaCommand;
use Maggie\Calendar\Message\UpdateAgendaCommand;
use Maggie\Calendar\Repository\AgendaRepository;
use Maggie\Core\Mcp\McpUserContext;
use Maggie\Core\Mcp\MissingMcpUserException;
use Mcp\Capability\Attribute\McpTool;
use Symfony\Component\Messenger\Exception\HandlerFailedException;
use Symfony\Component\Messenger\MessageBusInterface;
use Symfony\Component\Messenger\Stamp\HandledStamp;

#[McpTool(name: 'manage_agendas', description: 'List, create, update, or delete agendas (calendars). An agenda has a name, description, IANA time zone, colour, and a default flag. Events always belong to an agenda. On update, to empty an optional field, list its name in clear (description, color).')]
class ManageAgendasTool
{
    public function __construct(
        private readonly MessageBusInterface $bus,
        private readonly AgendaRepository $agendaRepository,
        private readonly McpUserContext $userContext,
    ) {
    }

    /** @param list<string>|null $clear */
    public function __invoke(
        string $action = 'list',
        ?string $agendaId = null,
        ?string $name = null,
        ?string $description = null,
        ?string $timeZone = null,
        ?string $color = null,
        ?bool $isDefault = null,
        ?array $clear = null,
    ): string {
        try {
            return match ($action) {
                'list' => $this->list(),
                'create' => $this->create($name, $description, $timeZone, $color, $isDefault),
                'update' => $this->update($agendaId, $name, $description, $timeZone, $color, $isDefault, $clear),
                'delete' => $this->delete($agendaId),
                default => json_encode(['error' => "Unknown action: {$action}. Use list, create, update, or delete."], JSON_THROW_ON_ERROR),
            };
        } catch (MissingMcpUserException $e) {
            return json_encode(['error' => $e->getMessage()], JSON_THROW_ON_ERROR);
        } catch (HandlerFailedException $e) {
            $cause = $e->getPrevious() ?? $e;

            return json_encode(['error' => $cause->getMessage()], JSON_THROW_ON_ERROR);
        }
    }

    private function list(): string
    {
        $user = $this->userContext->requireUser();

        $agendas = $this->agendaRepository->findByUser($user);

        return json_encode([
            'agendas' => array_map(fn (Agenda $a) => $this->serialize($a), $agendas),
            'count' => count($agendas),
        ], JSON_THROW_ON_ERROR);
    }

    private function create(?string $name, ?string $description, ?string $timeZone, ?string $color, ?bool $isDefault): string
    {
        if (null === $name) {
            return json_encode(['error' => 'Name is required for create.'], JSON_THROW_ON_ERROR);
        }

        $user = $this->userContext->requireUser();

        $envelope = $this->bus->dispatch(new CreateAgendaCommand(
            userId: (string) $user->getId(),
            name: $name,
            description: $description,
            timeZone: $timeZone ?? 'Europe/Paris',
            color: $color,
            isDefault: $isDefault ?? false,
        ));

        /** @var Agenda $agenda */
        $agenda = $envelope->last(HandledStamp::class)->getResult();

        return json_encode(['success' => true, 'agenda' => $this->serialize($agenda)], JSON_THROW_ON_ERROR);
    }

    /** @param list<string>|null $clear */
    private function update(?string $agendaId, ?string $name, ?string $description, ?string $timeZone, ?string $color, ?bool $isDefault, ?array $clear = null): string
    {
        if (null === $agendaId) {
            return json_encode(['error' => 'agendaId is required for update.'], JSON_THROW_ON_ERROR);
        }

        $envelope = $this->bus->dispatch(new UpdateAgendaCommand(
            agendaId: $agendaId,
            name: $name,
            description: $description,
            timeZone: $timeZone,
            color: $color,
            isDefault: $isDefault,
            clearFields: array_values(array_intersect($clear ?? [], ['description', 'color'])),
        ));

        /** @var Agenda $agenda */
        $agenda = $envelope->last(HandledStamp::class)->getResult();

        return json_encode(['success' => true, 'agenda' => $this->serialize($agenda)], JSON_THROW_ON_ERROR);
    }

    private function delete(?string $agendaId): string
    {
        if (null === $agendaId) {
            return json_encode(['error' => 'agendaId is required for delete.'], JSON_THROW_ON_ERROR);
        }

        $this->bus->dispatch(new DeleteAgendaCommand(agendaId: $agendaId));

        return json_encode(['success' => true], JSON_THROW_ON_ERROR);
    }

    /** @return array<string, mixed> */
    private function serialize(Agenda $agenda): array
    {
        return [
            'id' => (string) $agenda->getId(),
            'name' => $agenda->getName(),
            'description' => $agenda->getDescription(),
            'timeZone' => $agenda->getTimeZone(),
            'color' => $agenda->getColor(),
            'isDefault' => $agenda->isDefault(),
        ];
    }
}
