<?php

declare(strict_types=1);

namespace Maggie\Cookbook\Mcp\Tool;

use Maggie\Cookbook\Entity\CookingTimer;
use Maggie\Cookbook\Enum\CookingTimerAction;
use Maggie\Cookbook\Enum\CookingTimerStatus;
use Maggie\Cookbook\Message\ChangeCookingTimerCommand;
use Maggie\Cookbook\Message\StartCookingTimerCommand;
use Maggie\Cookbook\Repository\CookingTimerRepository;
use Maggie\Core\Mcp\McpUserContext;
use Maggie\Core\Mcp\MissingMcpUserException;
use Mcp\Capability\Attribute\McpTool;
use Symfony\Component\Clock\ClockInterface;
use Symfony\Component\Messenger\Exception\HandlerFailedException;
use Symfony\Component\Messenger\MessageBusInterface;
use Symfony\Component\Messenger\Stamp\HandledStamp;

/**
 * The timers of the Cuisine activity (MAG-582), for « il reste combien pour
 * les pâtes ? ». Nothing is deduced: a missing field is an error that comes
 * back with the timers as they stand, for Maggie to pick from.
 */
#[McpTool(name: 'manage_cooking_timers', description: 'List, start, pause, resume, add time to, or cancel the user\'s kitchen timers of the Cuisine activity. list returns the running, paused and done timers with label, step, remainingSeconds and endsAt — answer « how long left for the pasta? » from it. start needs label, recipeId, stepPosition and durationSeconds. pause, resume and cancel need timerId; add needs timerId and seconds (negative to remove time, 60 for one more minute). A refused call returns error plus the current timers: ask the user rather than guess.')]
class ManageCookingTimersTool
{
    public function __construct(
        private readonly MessageBusInterface $bus,
        private readonly CookingTimerRepository $timers,
        private readonly McpUserContext $userContext,
        private readonly ClockInterface $clock,
    ) {
    }

    public function __invoke(
        string $action,
        ?string $timerId = null,
        ?string $label = null,
        ?string $recipeId = null,
        ?int $stepPosition = null,
        ?int $durationSeconds = null,
        ?int $seconds = null,
    ): string {
        try {
            return match ($action) {
                'list' => $this->encode(['timers' => $this->active()]),
                'start' => $this->start($label, $recipeId, $stepPosition, $durationSeconds),
                'pause' => $this->change($timerId, CookingTimerAction::Pause),
                'resume' => $this->change($timerId, CookingTimerAction::Resume),
                'add' => null === $seconds ? $this->error('seconds is required for add (60 for one more minute, -60 for one less).') : $this->change($timerId, CookingTimerAction::Add, $seconds),
                'cancel' => $this->change($timerId, CookingTimerAction::Cancel),
                default => $this->error("Unknown action: {$action}. Use list, start, pause, resume, add, or cancel."),
            };
        } catch (MissingMcpUserException $e) {
            return $this->encode(['error' => $e->getMessage()]);
        } catch (HandlerFailedException $e) {
            return $this->error(($e->getPrevious() ?? $e)->getMessage());
        }
    }

    private function start(?string $label, ?string $recipeId, ?int $stepPosition, ?int $durationSeconds): string
    {
        $missing = array_keys(array_filter(
            ['label' => $label, 'recipeId' => $recipeId, 'stepPosition' => $stepPosition, 'durationSeconds' => $durationSeconds],
            static fn (mixed $value) => null === $value,
        ));
        if ([] !== $missing) {
            return $this->error(implode(', ', $missing).' required for start.');
        }
        \assert(null !== $label && null !== $recipeId && null !== $stepPosition && null !== $durationSeconds);

        $envelope = $this->bus->dispatch(new StartCookingTimerCommand(
            userId: (string) $this->userContext->requireUser()->getId(),
            label: $label,
            recipeId: $recipeId,
            stepPosition: $stepPosition,
            durationSeconds: $durationSeconds,
        ));

        return $this->answer($envelope->last(HandledStamp::class)?->getResult());
    }

    private function change(?string $timerId, CookingTimerAction $action, ?int $seconds = null): string
    {
        if (null === $timerId) {
            return $this->error("timerId is required for {$action->value}.");
        }

        $envelope = $this->bus->dispatch(new ChangeCookingTimerCommand(
            userId: (string) $this->userContext->requireUser()->getId(),
            timerId: $timerId,
            action: $action,
            seconds: $seconds,
        ));

        return $this->answer($envelope->last(HandledStamp::class)?->getResult());
    }

    private function answer(mixed $timer): string
    {
        \assert($timer instanceof CookingTimer);

        return $this->encode(['success' => true, 'timer' => $timer->describe($this->clock->now())]);
    }

    /** @return list<array<string, mixed>> */
    private function active(): array
    {
        $now = $this->clock->now();

        return array_map(
            static fn (CookingTimer $timer) => $timer->describe($now),
            $this->timers->findByUserAndStatuses($this->userContext->requireUser(), [CookingTimerStatus::Running, CookingTimerStatus::Paused, CookingTimerStatus::Done]),
        );
    }

    /** The refusal, and the timers as they stand. */
    private function error(string $message): string
    {
        return $this->encode(['error' => $message, 'timers' => $this->active()]);
    }

    /** @param array<string, mixed> $payload */
    private function encode(array $payload): string
    {
        return json_encode($payload, JSON_THROW_ON_ERROR);
    }
}
