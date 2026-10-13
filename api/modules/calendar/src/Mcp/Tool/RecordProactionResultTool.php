<?php

declare(strict_types=1);

namespace Maggie\Calendar\Mcp\Tool;

use Maggie\Calendar\Entity\Proaction;
use Maggie\Calendar\Message\RecordProactionResultCommand;
use Maggie\Calendar\Service\Proactions;
use Maggie\Core\Mcp\McpUserContext;
use Mcp\Capability\Attribute\McpTool;
use Symfony\Component\Messenger\Exception\HandlerFailedException;
use Symfony\Component\Messenger\MessageBusInterface;
use Symfony\Component\Messenger\Stamp\HandledStamp;

/** The executor's report of a run it claimed with claim_due_proactions (MAG-326). */
#[McpTool(name: 'record_proaction_result', description: 'Reserved for the proaction executor — never call it in a conversation. Records how a claimed (running) proaction went: status completed with response (Maggie\'s message, may be empty), or status failed with error.')]
class RecordProactionResultTool
{
    public function __construct(
        private readonly MessageBusInterface $bus,
        private readonly McpUserContext $userContext,
    ) {
    }

    public function __invoke(string $id, string $status, ?string $response = null, ?string $error = null): string
    {
        $user = $this->userContext->getUser();

        try {
            $envelope = $this->bus->dispatch(new RecordProactionResultCommand(
                userId: null !== $user ? (string) $user->getId() : null,
                proactionId: $id,
                status: $status,
                response: $response,
                error: $error,
            ));
        } catch (HandlerFailedException $e) {
            return json_encode(['error' => ($e->getPrevious() ?? $e)->getMessage()], JSON_THROW_ON_ERROR);
        }

        /** @var Proaction $proaction */
        $proaction = $envelope->last(HandledStamp::class)?->getResult();

        return json_encode(['success' => true, 'proaction' => Proactions::describe($proaction)], JSON_THROW_ON_ERROR);
    }
}
