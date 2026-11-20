<?php

declare(strict_types=1);

namespace Maggie\Calendar\Mcp\Tool;

use Maggie\Calendar\Entity\Proaction;
use Maggie\Calendar\Enum\ProactionKind;
use Maggie\Calendar\Message\ScheduleProactionCommand;
use Maggie\Calendar\Service\Proactions;
use Maggie\Core\Mcp\McpUserContext;
use Maggie\Core\Mcp\MissingMcpUserException;
use Mcp\Capability\Attribute\McpTool;
use Symfony\Component\Messenger\Exception\HandlerFailedException;
use Symfony\Component\Messenger\MessageBusInterface;
use Symfony\Component\Messenger\Stamp\HandledStamp;

#[McpTool(name: 'schedule_proaction', description: 'Schedules ONE run of Maggie: at scheduled_at she receives prompt as instruction. Filed as a five-minute event of the internal Maggie agenda (not synced with Google). Single occurrence; no RRULE. scheduled_at: ISO 8601 with time, e.g. 2037-01-15T08:30:00+01:00, Europe/Paris without offset, in the future. prompt: self-contained. summary: optional agenda label. kind: reminder (default — the owner asked to be told at that time; always interrupts) or check (verifies or watches; speaks only when useful, triaged). Returns the proaction (proaction.scheduledAt).')]
class ScheduleProactionTool
{
    public function __construct(
        private readonly Proactions $proactions,
        private readonly MessageBusInterface $bus,
        private readonly McpUserContext $userContext,
    ) {
    }

    public function __invoke(string $prompt, string $scheduled_at, ?string $summary = null, ?string $kind = null): string
    {
        try {
            $user = $this->userContext->requireUser();
            $envelope = $this->bus->dispatch(new ScheduleProactionCommand(
                userId: (string) $user->getId(),
                prompt: $prompt,
                scheduledAt: $this->proactions->instantOf($scheduled_at),
                summary: $summary,
                kind: ProactionKind::fromInput($kind),
            ));
        } catch (MissingMcpUserException|\DomainException $e) {
            return json_encode(['error' => $e->getMessage()], JSON_THROW_ON_ERROR);
        } catch (HandlerFailedException $e) {
            return json_encode(['error' => ($e->getPrevious() ?? $e)->getMessage()], JSON_THROW_ON_ERROR);
        }

        /** @var Proaction $proaction */
        $proaction = $envelope->last(HandledStamp::class)?->getResult();

        return json_encode([
            'success' => true,
            'proaction' => Proactions::describe($proaction),
            'agenda' => $proaction->getAgenda()->getName(),
        ], JSON_THROW_ON_ERROR);
    }
}
