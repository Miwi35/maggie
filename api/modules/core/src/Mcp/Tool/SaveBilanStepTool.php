<?php

declare(strict_types=1);

namespace Maggie\Core\Mcp\Tool;

use Maggie\Core\Entity\ActivityStatus;
use Maggie\Core\Enum\Activity;
use Maggie\Core\Mcp\McpUserContext;
use Maggie\Core\Mcp\MissingMcpUserException;
use Maggie\Core\Message\SaveActivityStatusCommand;
use Maggie\Core\Repository\ActivityStatusRepository;
use Maggie\Core\Service\BilanStateValidator;
use Mcp\Capability\Attribute\McpTool;
use Symfony\Component\Messenger\Exception\HandlerFailedException;
use Symfony\Component\Messenger\MessageBusInterface;
use Symfony\Component\Messenger\Stamp\HandledStamp;

#[McpTool(name: 'save_bilan_step', description: 'Record where the evening review (« Bilan du soir ») stands, at every passage from one step to the next, so any device resumes it there. day is the day of the review, YYYY-MM-DD. step is one of journee, finance, sante, consignes, demain, in that order. answers (optional) is {tasks: [{taskId, choice: done|tomorrow|later}], mood, fatigue, note} and instructions (optional) a list of {id, text, kind: reminder|task|instruction, targetId?}: when omitted, what is already stored for the same day is kept; a new day starts from nothing. Returns the status as saved, or {error}.')]
class SaveBilanStepTool
{
    public function __construct(
        private readonly ActivityStatusRepository $repository,
        private readonly MessageBusInterface $bus,
        private readonly McpUserContext $userContext,
    ) {
    }

    /**
     * @param array<string, mixed>|null       $answers
     * @param list<array<string, mixed>>|null $instructions
     */
    public function __invoke(string $day, string $step, ?array $answers = null, ?array $instructions = null): string
    {
        try {
            $user = $this->userContext->requireUser();
        } catch (MissingMcpUserException $e) {
            return json_encode(['error' => $e->getMessage()], JSON_THROW_ON_ERROR);
        }

        $stored = $this->repository->findOneByUserAndActivity($user, Activity::Bilan);
        $previous = null !== $stored && 'day' === $stored->getOriginKind() && $stored->getOriginId() === $day
            ? json_decode($stored->getState(), true, 512, JSON_THROW_ON_ERROR)
            : [];

        $state = [
            'step' => $step,
            'answers' => $answers ?? $previous['answers'] ?? new \stdClass(),
            'instructions' => $instructions ?? $previous['instructions'] ?? [],
        ];
        $encoded = json_encode($state, JSON_THROW_ON_ERROR | JSON_UNESCAPED_SLASHES | JSON_UNESCAPED_UNICODE | JSON_PRESERVE_ZERO_FRACTION);

        $error = BilanStateValidator::validate($day, json_decode($encoded, false, 512, JSON_THROW_ON_ERROR));
        if (null === $error && \strlen($encoded) > ActivityStatus::MAX_STATE_BYTES) {
            $error = \sprintf('The state must be at most %d bytes of JSON.', ActivityStatus::MAX_STATE_BYTES);
        }
        if (null !== $error) {
            return json_encode(['error' => $error], JSON_THROW_ON_ERROR);
        }

        try {
            $status = $this->bus->dispatch(new SaveActivityStatusCommand(
                userId: (string) $user->getId(),
                activity: Activity::Bilan,
                originKind: 'day',
                originId: $day,
                state: $encoded,
            ))->last(HandledStamp::class)?->getResult();
        } catch (HandlerFailedException $e) {
            return json_encode(['error' => ($e->getPrevious() ?? $e)->getMessage()], JSON_THROW_ON_ERROR);
        }
        \assert($status instanceof ActivityStatus);

        return json_encode(['success' => true, 'status' => $status->describe()], JSON_THROW_ON_ERROR);
    }
}
