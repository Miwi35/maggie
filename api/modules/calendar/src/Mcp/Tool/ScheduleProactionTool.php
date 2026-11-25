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
use Mcp\Capability\Attribute\Schema;
use Symfony\Component\Messenger\Exception\HandlerFailedException;
use Symfony\Component\Messenger\MessageBusInterface;
use Symfony\Component\Messenger\Stamp\HandledStamp;

#[McpTool(name: 'schedule_proaction', description: 'For what Maggie herself must do or say at that time; a thing the owner has to do is a task (create_task). A standing rule about when Maggie acts, which keeps coming back, is a planning directive (add_instruction), read by her daily planning; a push sent now is manage_notifications. Before: list_proactions around that time, a run already there is not doubled. Schedules a run of Maggie, or a series of runs with rrule (RFC 5545 without RRULE:, FREQ HOURLY to YEARLY, 500 characters at most; scheduled_at must be its first occurrence, else an error names it): at scheduled_at she receives prompt as instruction. Filed as a five-minute event of the internal Maggie agenda (not synced with Google). scheduled_at: ISO 8601 with time, e.g. 2037-01-15T08:30:00+01:00, Europe/Paris without offset, in the future. prompt: self-contained. summary: optional agenda label. kind: reminder (default — the owner asked to be told at that time; always interrupts) or check (verifies or watches; speaks only when useful, triaged). Returns the proaction (proaction.scheduledAt).')]
class ScheduleProactionTool
{
    public function __construct(
        private readonly Proactions $proactions,
        private readonly MessageBusInterface $bus,
        private readonly McpUserContext $userContext,
    ) {
    }

    /** @param array<mixed>|null $origin */
    public function __invoke(
        string $prompt,
        string $scheduled_at,
        ?string $summary = null,
        ?string $kind = null,
        ?string $rrule = null,
        // The tool's description does not name it: the model never learns it (MAG-977).
        #[Schema(type: 'object', properties: ['kind' => ['type' => 'string', 'enum' => Proaction::ORIGIN_KINDS], 'ref' => ['type' => 'string', 'maxLength' => Proaction::ORIGIN_REF_LENGTH]], required: ['kind', 'ref'], description: 'Set by the agent code from the owner message, never by the model.')]
        ?array $origin = null,
    ): string {
        try {
            $user = $this->userContext->requireUser();
            $envelope = $this->bus->dispatch(new ScheduleProactionCommand(
                userId: (string) $user->getId(),
                prompt: $prompt,
                scheduledAt: $this->proactions->instantOf($scheduled_at),
                summary: $summary,
                kind: ProactionKind::fromInput($kind),
                rrule: null !== $rrule && '' !== trim($rrule) ? trim($rrule) : null,
                origin: $origin,
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
