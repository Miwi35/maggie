<?php

declare(strict_types=1);

namespace Maggie\Core\Mcp\Tool;

use Maggie\Core\Entity\UserPreference;
use Maggie\Core\Mcp\McpUserContext;
use Maggie\Core\Mcp\MissingMcpUserException;
use Maggie\Core\Message\SetSickCommand;
use Mcp\Capability\Attribute\McpTool;
use Symfony\Component\Messenger\Exception\HandlerFailedException;
use Symfony\Component\Messenger\MessageBusInterface;
use Symfony\Component\Messenger\Stamp\HandledStamp;

#[McpTool(name: 'set_sick', description: 'Record that the user is sick (sick: true) or that it is over (sick: false). Sick, the state holds until they say it is over: call it with true when they tell you they are ill, with false when they are better. Called again with true while sick, the first day is kept. Returns {sick, sickSince}: sickSince is the first day, YYYY-MM-DD in their timezone, null when not sick.')]
final class SetSickTool
{
    public function __construct(
        private readonly McpUserContext $userContext,
        private readonly MessageBusInterface $bus,
    ) {
    }

    public function __invoke(bool $sick): string
    {
        try {
            $user = $this->userContext->requireUser();
        } catch (MissingMcpUserException $e) {
            return json_encode(['error' => $e->getMessage()], JSON_THROW_ON_ERROR);
        }

        try {
            $envelope = $this->bus->dispatch(new SetSickCommand((string) $user->getId(), $sick));
        } catch (HandlerFailedException $e) {
            // A refusal is Maggie's to read; anything else is ours.
            $cause = $e->getPrevious() ?? $e;
            if (!$cause instanceof \DomainException) {
                throw $e;
            }

            return json_encode(['error' => $cause->getMessage()], JSON_THROW_ON_ERROR);
        }
        /** @var UserPreference $preference */
        $preference = $envelope->last(HandledStamp::class)?->getResult();
        $sickSince = $preference->getSickSince()?->format('Y-m-d');

        return json_encode(['sick' => null !== $sickSince, 'sickSince' => $sickSince], JSON_THROW_ON_ERROR);
    }
}
