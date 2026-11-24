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
#[McpTool(name: 'claim_due_proactions', description: 'Atomically claims the pending proactions whose time has come (or the one given by id, due or not) and marks them running, each returned with its userId, prompt, kind and origin (null when unknown); a check also carries signaledTopics, the topics delivered to its user these last 7 days ({topic, at, delivery, notice}) and checkedValues, the last value verified per topic these last 40 days ({topic, value, at}). A due occurrence of a series is returned as its own proaction, with seriesId and occurrence; the series itself is never claimed. Executor only, never offered to the model.')]
class ClaimDueProactionsTool
{
    public function __construct(
        private readonly MessageBusInterface $bus,
        private readonly McpUserContext $userContext,
        private readonly Proactions $proactions,
    ) {
    }

    public function __invoke(?int $limit = null, ?string $id = null): string
    {
        $user = $this->userContext->getUser();
        $envelope = $this->bus->dispatch(new ClaimDueProactionsCommand(
            userId: null !== $user ? (string) $user->getId() : null,
            limit: $limit ?? Proactions::CLAIM_LIMIT,
            proactionId: null !== $id && '' !== trim($id) ? trim($id) : null,
        ));

        /** @var Proaction[] $claimed */
        $claimed = $envelope->last(HandledStamp::class)?->getResult() ?? [];

        return json_encode([
            'proactions' => array_map(function (Proaction $p): array {
                $described = Proactions::describe($p);
                $signaled = $this->proactions->signaledTopics($p);
                if (null !== $signaled) {
                    $described['signaledTopics'] = $signaled;
                }

                $checkedValues = $this->proactions->checkedValues($p);
                if (null !== $checkedValues) {
                    $described['checkedValues'] = $checkedValues;
                }

                return $described;
            }, $claimed),
            'count' => count($claimed),
        ], JSON_THROW_ON_ERROR);
    }
}
