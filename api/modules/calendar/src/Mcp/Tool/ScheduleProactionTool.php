<?php

declare(strict_types=1);

namespace Maggie\Calendar\Mcp\Tool;

use Maggie\Calendar\Entity\Proaction;
use Maggie\Calendar\Message\ScheduleProactionCommand;
use Maggie\Calendar\Service\Proactions;
use Maggie\Core\Mcp\McpUserContext;
use Maggie\Core\Mcp\MissingMcpUserException;
use Mcp\Capability\Attribute\McpTool;
use Symfony\Component\Messenger\Exception\HandlerFailedException;
use Symfony\Component\Messenger\MessageBusInterface;
use Symfony\Component\Messenger\Stamp\HandledStamp;

#[McpTool(name: 'schedule_proaction', description: 'Schedule ONE run of Maggie at a given moment: at scheduled_at, Maggie receives prompt as her instruction and acts — remind, check, report. It is filed as an event of the user\'s internal « Maggie » agenda (never synced with Google), five minutes long, so the user sees it and can move or delete it there. Choose between the two first: anything lasting or repeating (« tous les lundis », « les jours où j\'ai Ael », « préviens-moi toujours… ») is an instruction — use add_instruction, never a proaction and never an RRULE; a single occurrence (« demain à 8 h 30 », « dans une heure ») is a proaction. scheduled_at is ISO 8601 with a time, e.g. 2037-01-15T08:30:00+01:00, read in Europe/Paris when it has no offset; it must be in the future. prompt is what Maggie will do, written for herself with everything she will need. summary is the short label shown in the agenda (optional, taken from the prompt). Returns the proaction as saved (proaction.scheduledAt): announce exactly that.')]
class ScheduleProactionTool
{
    public function __construct(
        private readonly Proactions $proactions,
        private readonly MessageBusInterface $bus,
        private readonly McpUserContext $userContext,
    ) {
    }

    public function __invoke(string $prompt, string $scheduled_at, ?string $summary = null): string
    {
        try {
            $user = $this->userContext->requireUser();
            $envelope = $this->bus->dispatch(new ScheduleProactionCommand(
                userId: (string) $user->getId(),
                prompt: $prompt,
                scheduledAt: $this->proactions->instantOf($scheduled_at),
                summary: $summary,
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
