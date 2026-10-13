<?php

declare(strict_types=1);

namespace Maggie\Calendar\Mcp\Tool;

use Maggie\Calendar\Entity\Proaction;
use Maggie\Calendar\Message\ClaimDueProactionsCommand;
use Maggie\Calendar\Service\Proactions;
use Maggie\Core\Mcp\McpUserContext;
use Mcp\Capability\Attribute\McpTool;
use Symfony\Component\Messenger\MessageBusInterface;
use Symfony\Component\Messenger\Stamp\HandledStamp;

/**
 * The executor's door (MAG-326): with the service token and no user named, it
 * claims the due proactions of every user — what that credential can already do
 * one user at a time through X-Maggie-User-Id. A caller acting for a user only
 * ever claims that user's.
 */
#[McpTool(name: 'claim_due_proactions', description: 'Reserved for the proaction executor — never call it in a conversation. Claims the pending proactions whose time has come and marks them running, atomically: a proaction is claimed once, however many executors ask. Each comes with its userId and prompt; report the outcome with record_proaction_result.')]
class ClaimDueProactionsTool
{
    public function __construct(
        private readonly MessageBusInterface $bus,
        private readonly McpUserContext $userContext,
    ) {
    }

    public function __invoke(?int $limit = null): string
    {
        $user = $this->userContext->getUser();
        $envelope = $this->bus->dispatch(new ClaimDueProactionsCommand(
            userId: null !== $user ? (string) $user->getId() : null,
            limit: $limit ?? Proactions::CLAIM_LIMIT,
        ));

        /** @var Proaction[] $claimed */
        $claimed = $envelope->last(HandledStamp::class)?->getResult() ?? [];

        return json_encode([
            'proactions' => array_map(static fn (Proaction $p) => Proactions::describe($p), $claimed),
            'count' => count($claimed),
        ], JSON_THROW_ON_ERROR);
    }
}
