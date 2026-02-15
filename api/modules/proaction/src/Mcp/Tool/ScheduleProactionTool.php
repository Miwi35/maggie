<?php

declare(strict_types=1);

namespace Maggie\Proaction\Mcp\Tool;

use Maggie\Proaction\Entity\Proaction;
use Maggie\Proaction\Message\CreateProactionCommand;
use Mcp\Capability\Attribute\McpTool;
use Symfony\Component\Messenger\Exception\HandlerFailedException;
use Symfony\Component\Messenger\MessageBusInterface;
use Symfony\Component\Messenger\Stamp\HandledStamp;

#[McpTool(name: 'schedule_proaction', description: 'Schedule an autonomous proaction for the agent to execute at a given time. The agent will be called with the prompt at the scheduled time. Date format: ISO 8601 (e.g. 2026-02-16T08:00:00+01:00).')]
class ScheduleProactionTool
{
    public function __construct(
        private readonly MessageBusInterface $bus,
    ) {
    }

    public function __invoke(string $scheduledAt, string $prompt): string
    {
        try {
            $dateTime = new \DateTimeImmutable($scheduledAt);
        } catch (\Exception $e) {
            return json_encode(['error' => 'Invalid date format: ' . $e->getMessage()], JSON_THROW_ON_ERROR);
        }

        try {
            $envelope = $this->bus->dispatch(new CreateProactionCommand(
                scheduledAt: $dateTime,
                prompt: $prompt,
            ));

            /** @var Proaction $proaction */
            $proaction = $envelope->last(HandledStamp::class)->getResult();

            return json_encode([
                'success' => true,
                'proaction' => [
                    'id' => (string) $proaction->getId(),
                    'scheduledAt' => $proaction->getScheduledAt()->format('c'),
                    'prompt' => $proaction->getPrompt(),
                    'status' => $proaction->getStatus()->value,
                ],
            ], JSON_THROW_ON_ERROR);
        } catch (HandlerFailedException $e) {
            $cause = $e->getPrevious() ?? $e;
            return json_encode(['error' => $cause->getMessage()], JSON_THROW_ON_ERROR);
        }
    }
}
