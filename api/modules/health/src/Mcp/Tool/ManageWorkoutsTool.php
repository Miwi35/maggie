<?php

declare(strict_types=1);

namespace Maggie\Health\Mcp\Tool;

use Maggie\Core\Mcp\McpUserContext;
use Maggie\Core\Mcp\MissingMcpUserException;
use Maggie\Health\Exception\InvalidWorkoutException;
use Maggie\Health\Exception\WorkoutScheduleException;
use Maggie\Health\Mcp\WorkoutJson;
use Maggie\Health\Message\CreateWorkoutCommand;
use Maggie\Health\Message\DeleteWorkoutCommand;
use Maggie\Health\Message\UpdateWorkoutCommand;
use Maggie\Health\Repository\WorkoutRepository;
use Maggie\Health\Service\WorkoutRules;
use Mcp\Capability\Attribute\McpTool;
use Mcp\Capability\Attribute\Schema;
use Symfony\Component\Messenger\Exception\HandlerFailedException;
use Symfony\Component\Messenger\MessageBusInterface;
use Symfony\Component\Messenger\Stamp\HandledStamp;

/**
 * Never completes a request by deduction: a missing field is named, a partial
 * update is refused with `current`, the workout as it is, so the next call can
 * be whole.
 */
#[McpTool(name: 'manage_workouts', description: 'Actions: list, create, update, delete. Fields: type (run, walk, bike, swim, strength, climbing, other), title, startedAt (ISO 8601 with offset), durationMinutes, distanceMeters (metres), kcal, avgHeartRate, feeling (very_good, good, hard, very_hard), source (manual default, watch, activity, estimated, planned = still to do), externalId, difficulty (1-10), variant (normal, calibration, soft, deload), exercises: ordered list, always sent WHOLE, replaces the previous one. Exercise: exercise (slug), step, sets, target (per set), targetMin, targetMax, restSeconds, tempo (true = 3 s down, 1 s pause), loadKg, targetRir, cue, feeling (too_easy, right, too_hard), done (list of {reps}, {seconds} or {meters}, plus loadKg); both done only. create needs type, title, startedAt, durationMinutes. update, delete need workoutId; update changes given fields only. list filters type, source, exercise (slug), after, before (on startedAt), limit (max 100). Returns {workouts} or {success, workout}. Refusal (nothing changes): {error, field?, current?}. A planned strength session is refused (current = the week\'s strength sessions) if the week has 3, it is under 24 h from another, or it is the 3rd with an eccentric exercise or under 48 h from another.')]
final class ManageWorkoutsTool
{
    public function __construct(
        private readonly McpUserContext $userContext,
        private readonly MessageBusInterface $bus,
        private readonly WorkoutRepository $workoutRepository,
        private readonly WorkoutRules $rules,
    ) {
    }

    /** @param list<array<string, mixed>>|null $exercises the list as the agent sent it, checked by the handler */
    public function __invoke(
        #[Schema(enum: ['list', 'create', 'update', 'delete'])]
        string $action,
        ?string $workoutId = null,
        #[Schema(enum: ['run', 'walk', 'bike', 'swim', 'strength', 'climbing', 'other'])]
        ?string $type = null,
        ?string $title = null,
        ?string $startedAt = null,
        ?int $durationMinutes = null,
        ?int $distanceMeters = null,
        ?int $kcal = null,
        ?int $avgHeartRate = null,
        #[Schema(enum: ['very_good', 'good', 'hard', 'very_hard'])]
        ?string $feeling = null,
        ?string $notes = null,
        #[Schema(enum: ['manual', 'watch', 'activity', 'estimated', 'planned'])]
        ?string $source = null,
        ?string $externalId = null,
        ?int $difficulty = null,
        #[Schema(enum: ['normal', 'calibration', 'soft', 'deload'])]
        ?string $variant = null,
        #[Schema(items: ['type' => 'object', 'properties' => [
            'exercise' => ['type' => 'string'],
            'step' => ['type' => 'integer'],
            'sets' => ['type' => 'integer'],
            'target' => ['type' => 'integer'],
            'targetMin' => ['type' => 'integer'],
            'targetMax' => ['type' => 'integer'],
            'loadKg' => ['type' => 'number'],
            'restSeconds' => ['type' => 'integer'],
            'tempo' => ['type' => 'boolean'],
            'targetRir' => ['type' => 'integer'],
            'cue' => ['type' => 'string'],
            'feeling' => ['type' => 'string', 'enum' => ['too_easy', 'right', 'too_hard']],
            'done' => ['type' => 'array', 'items' => ['type' => 'object', 'properties' => [
                'reps' => ['type' => 'integer'],
                'seconds' => ['type' => 'integer'],
                'meters' => ['type' => 'integer'],
                'loadKg' => ['type' => 'number'],
            ]]],
        ], 'required' => ['exercise', 'step', 'sets', 'target', 'targetMin', 'targetMax', 'restSeconds']])]
        ?array $exercises = null,
        ?string $exercise = null,
        ?string $after = null,
        ?string $before = null,
        ?int $limit = null,
    ): string {
        try {
            return match ($action) {
                'list' => $this->list($type, $source, $exercise, $after, $before, $limit),
                'create' => $this->create($type, $title, $startedAt, $durationMinutes, $distanceMeters, $kcal, $avgHeartRate, $feeling, $notes, $source, $externalId, $difficulty, $variant, $exercises),
                'update' => $this->update($workoutId, $type, $title, $startedAt, $durationMinutes, $distanceMeters, $kcal, $avgHeartRate, $feeling, $notes, $source, $externalId, $difficulty, $variant, $exercises),
                'delete' => $this->delete($workoutId),
                default => self::json(['error' => "Unknown action: {$action}. Use list, create, update or delete."]),
            };
        } catch (MissingMcpUserException $e) {
            return self::json(['error' => $e->getMessage()]);
        } catch (InvalidWorkoutException $e) {
            return self::json(['error' => $e->getMessage(), 'field' => $e->field]);
        }
    }

    private function list(?string $type, ?string $source, ?string $exercise, ?string $after, ?string $before, ?int $limit): string
    {
        $user = $this->userContext->requireUser();

        $workouts = $this->workoutRepository->findByUser(
            $user,
            null === $type ? null : $this->rules->type($type),
            null === $source ? null : $this->rules->source($source),
            null === $after ? null : $this->filterDate('after', $after),
            null === $before ? null : $this->filterDate('before', $before),
            max(1, min($limit ?? 20, 100)),
            $exercise,
        );

        return self::json(['workouts' => WorkoutJson::list($workouts)]);
    }

    /** @param list<mixed>|null $exercises */
    private function create(?string $type, ?string $title, ?string $startedAt, ?int $durationMinutes, ?int $distanceMeters, ?int $kcal, ?int $avgHeartRate, ?string $feeling, ?string $notes, ?string $source, ?string $externalId, ?int $difficulty, ?string $variant, ?array $exercises): string
    {
        $user = $this->userContext->requireUser();

        foreach (['type' => $type, 'title' => $title, 'startedAt' => $startedAt, 'durationMinutes' => $durationMinutes] as $field => $value) {
            if (null === $value) {
                return self::json(['error' => "{$field} is required. Ask the user, never assume.", 'field' => $field]);
            }
        }
        \assert(null !== $type && null !== $title && null !== $startedAt && null !== $durationMinutes);

        try {
            $workout = $this->bus->dispatch(new CreateWorkoutCommand(
                userId: (string) $user->getId(),
                type: $type,
                title: $title,
                startedAt: $startedAt,
                durationMinutes: $durationMinutes,
                distanceMeters: $distanceMeters,
                kcal: $kcal,
                avgHeartRate: $avgHeartRate,
                feeling: $feeling,
                notes: $notes,
                source: $source ?? 'manual',
                externalId: $externalId,
                exercises: $exercises,
                difficulty: $difficulty,
                variant: $variant,
            ))->last(HandledStamp::class)?->getResult();
        } catch (HandlerFailedException $e) {
            return self::refusal($e);
        }

        return self::json(['success' => true, 'workout' => WorkoutJson::of($workout)]);
    }

    /** @param list<mixed>|null $exercises */
    private function update(?string $workoutId, ?string $type, ?string $title, ?string $startedAt, ?int $durationMinutes, ?int $distanceMeters, ?int $kcal, ?int $avgHeartRate, ?string $feeling, ?string $notes, ?string $source, ?string $externalId, ?int $difficulty, ?string $variant, ?array $exercises): string
    {
        if (null === $workoutId) {
            return self::json(['error' => 'workoutId is required for update.', 'field' => 'workoutId']);
        }

        $user = $this->userContext->requireUser();
        $before = $this->workoutRepository->findOneOfUser($user, $workoutId);
        if (null === $before) {
            return self::json(['error' => "Workout not found: {$workoutId}"]);
        }
        // Read before the handler touches the entity: the state it is refused against.
        $current = WorkoutJson::of($before);

        $given = [$type, $title, $startedAt, $durationMinutes, $distanceMeters, $kcal, $avgHeartRate, $feeling, $notes, $source, $externalId, $difficulty, $variant, $exercises];
        if ([] === array_filter($given, static fn (mixed $value): bool => null !== $value)) {
            return self::json(['error' => 'Nothing to change: give at least one field to update.', 'current' => $current]);
        }

        try {
            $workout = $this->bus->dispatch(new UpdateWorkoutCommand(
                userId: (string) $user->getId(),
                workoutId: $workoutId,
                type: $type,
                title: $title,
                startedAt: $startedAt,
                durationMinutes: $durationMinutes,
                distanceMeters: $distanceMeters,
                kcal: $kcal,
                avgHeartRate: $avgHeartRate,
                feeling: $feeling,
                notes: $notes,
                source: $source,
                externalId: $externalId,
                exercises: $exercises,
                difficulty: $difficulty,
                variant: $variant,
            ))->last(HandledStamp::class)?->getResult();
        } catch (HandlerFailedException $e) {
            return self::refusal($e, $current);
        }

        return self::json(['success' => true, 'workout' => WorkoutJson::of($workout)]);
    }

    private function delete(?string $workoutId): string
    {
        if (null === $workoutId) {
            return self::json(['error' => 'workoutId is required for delete.', 'field' => 'workoutId']);
        }

        try {
            $this->bus->dispatch(new DeleteWorkoutCommand(
                userId: (string) $this->userContext->requireUser()->getId(),
                workoutId: $workoutId,
            ));
        } catch (HandlerFailedException $e) {
            return self::refusal($e);
        }

        return self::json(['success' => true]);
    }

    private function filterDate(string $field, string $value): \DateTimeImmutable
    {
        try {
            return $this->rules->startedAt($value);
        } catch (InvalidWorkoutException) {
            throw new InvalidWorkoutException($field, 'must be ISO 8601 with time and offset, e.g. 2026-10-20T00:00:00+02:00.');
        }
    }

    /**
     * A domain refusal is Maggie's to read; anything else is ours.
     *
     * @param array<string, mixed>|null $current
     */
    private static function refusal(HandlerFailedException $e, ?array $current = null): string
    {
        $cause = $e->getPrevious() ?? $e;
        if (!$cause instanceof \DomainException) {
            throw $e;
        }

        $data = ['error' => $cause->getMessage()];
        if ($cause instanceof InvalidWorkoutException && null !== $cause->field) {
            $data['field'] = $cause->field;
        }
        if ($cause instanceof WorkoutScheduleException) {
            $data['current'] = $cause->current;
        } elseif (null !== $current) {
            $data['current'] = $current;
        }

        return self::json($data);
    }

    /** @param array<string, mixed> $data */
    private static function json(array $data): string
    {
        return json_encode($data, \JSON_THROW_ON_ERROR);
    }
}
