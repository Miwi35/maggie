<?php

declare(strict_types=1);

namespace Maggie\Health\Mcp\Tool;

use Maggie\Core\Mcp\McpUserContext;
use Maggie\Core\Mcp\MissingMcpUserException;
use Maggie\Health\Entity\WorkoutMakeup;
use Maggie\Health\Exception\InvalidWorkoutMakeupException;
use Maggie\Health\Exception\WorkoutMakeupConflictException;
use Maggie\Health\Exception\WorkoutMakeupNotFoundException;
use Maggie\Health\Mcp\WorkoutJson;
use Maggie\Health\Message\ChooseWorkoutMakeupCommand;
use Mcp\Capability\Attribute\McpTool;
use Symfony\Component\Messenger\Exception\HandlerFailedException;
use Symfony\Component\Messenger\MessageBusInterface;
use Symfony\Component\Messenger\Stamp\HandledStamp;

#[McpTool(name: 'choose_workout_makeup', description: 'Answer a « Séance manquée » proposal (a workout_makeup notification, whose relatedEntityIri is the proposal): plan the make-up the user chose. makeup_id is the proposal\'s id or IRI; option is softer (the same session tonight at the next quarter hour), shorter (a shorter one tonight) or tomorrow (the same session tomorrow at the same hour). Creates a planned workout and returns {success, makeup, workout}. A proposal answers once: already chosen, or obsolete because a session done kept the missed one → {error, status}. Never reproach a missed session.')]
final class ChooseWorkoutMakeupTool
{
    public function __construct(
        private readonly MessageBusInterface $bus,
        private readonly McpUserContext $userContext,
    ) {
    }

    public function __invoke(string $makeup_id, string $option): string
    {
        try {
            $user = $this->userContext->requireUser();
            $makeup = $this->bus->dispatch(new ChooseWorkoutMakeupCommand((string) $user->getId(), $makeup_id, $option))->last(HandledStamp::class)?->getResult();
        } catch (MissingMcpUserException $e) {
            return self::json(['error' => $e->getMessage()]);
        } catch (HandlerFailedException $e) {
            $cause = $e->getPrevious() ?? $e;

            return match (true) {
                $cause instanceof InvalidWorkoutMakeupException => self::json(['error' => $cause->getMessage(), 'field' => $cause->field]),
                $cause instanceof WorkoutMakeupNotFoundException => self::json(['error' => $cause->getMessage()]),
                $cause instanceof WorkoutMakeupConflictException => self::json(['error' => $cause->getMessage(), 'status' => 409]),
                default => throw $e,
            };
        }
        \assert($makeup instanceof WorkoutMakeup);

        $workout = $makeup->getChosenWorkout();

        return self::json([
            'success' => true,
            'makeup' => $makeup->toMercurePayload(),
            'workout' => null === $workout ? null : WorkoutJson::of($workout),
        ]);
    }

    /** @param array<string, mixed> $data */
    private static function json(array $data): string
    {
        return json_encode($data, JSON_THROW_ON_ERROR);
    }
}
