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
use Mcp\Capability\Attribute\Schema;
use Symfony\Component\Messenger\Exception\HandlerFailedException;
use Symfony\Component\Messenger\MessageBusInterface;
use Symfony\Component\Messenger\Stamp\HandledStamp;

#[McpTool(name: 'save_bilan_step', description: 'Evening review state only; a consigne of the review is add_bilan_consigne. Records the evening review state. day: YYYY-MM-DD. step (optional): journee, finance, sante, consignes, demain; omitted keeps the stored step, a bilan prepared before it starts has none. tomorrowTasks, tomorrowSpares (optional): [{taskId, title, reason?}], the tasks Maggie plans for tomorrow and the ones she keeps to swap in; omitted keeps them. answers (optional): {tasks: [{taskId, choice: done|tomorrow|later}], mood, fatigue, note}; instructions (optional): [{id, text, kind: reminder|task|instruction, targetId?}]; omitted keeps stored values of that day. Returns the status, or {error}.')]
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
     * @param list<array<string, mixed>>|null $tomorrowTasks
     * @param list<array<string, mixed>>|null $tomorrowSpares
     */
    public function __invoke(
        string $day,
        ?string $step = null,
        #[Schema(type: 'object')]
        ?array $answers = null,
        ?array $instructions = null,
        #[Schema(type: 'array')]
        ?array $tomorrowTasks = null,
        #[Schema(type: 'array')]
        ?array $tomorrowSpares = null,
    ): string {
        try {
            $user = $this->userContext->requireUser();
        } catch (MissingMcpUserException $e) {
            return json_encode(['error' => $e->getMessage()], JSON_THROW_ON_ERROR);
        }

        $stored = $this->repository->findOneByUserAndActivity($user, Activity::Bilan);
        $previous = null !== $stored && 'day' === $stored->getOriginKind() && $stored->getOriginId() === $day
            ? json_decode($stored->getState(), true, 512, JSON_THROW_ON_ERROR)
            : [];

        $answers ??= $previous['answers'] ?? [];
        if ([] === $answers) {
            $answers = new \stdClass();
        } elseif (array_is_list($answers)) {
            $answers = ['tasks' => $answers];
        }

        $state = [
            'answers' => $answers,
            'instructions' => $instructions ?? $previous['instructions'] ?? [],
        ];
        $step ??= $previous['step'] ?? null;
        if (null !== $step) {
            $state = ['step' => $step] + $state;
        }
        foreach (['tomorrowTasks' => $tomorrowTasks, 'tomorrowSpares' => $tomorrowSpares] as $key => $value) {
            $value ??= $previous[$key] ?? null;
            if (null !== $value) {
                $state[$key] = $value;
            }
        }
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
