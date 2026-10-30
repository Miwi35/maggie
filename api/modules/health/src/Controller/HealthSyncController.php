<?php

declare(strict_types=1);

namespace Maggie\Health\Controller;

use Maggie\Core\Entity\User;
use Maggie\Health\Dto\HealthSyncRequest;
use Maggie\Health\Dto\HealthSyncResult;
use Maggie\Health\Dto\SyncedDay;
use Maggie\Health\Dto\SyncedMeasurement;
use Maggie\Health\Dto\SyncedWorkout;
use Maggie\Health\Message\SyncHealthDataCommand;
use Symfony\Bundle\SecurityBundle\Security;
use Symfony\Component\HttpFoundation\JsonResponse;
use Symfony\Component\HttpFoundation\Request;
use Symfony\Component\HttpFoundation\Response;
use Symfony\Component\Messenger\Exception\HandlerFailedException;
use Symfony\Component\Messenger\MessageBusInterface;
use Symfony\Component\Messenger\Stamp\HandledStamp;
use Symfony\Component\Routing\Attribute\Route;
use Symfony\Component\Validator\Validator\ValidatorInterface;

/**
 * What the phone read in Health Connect (MAG-708): the watch's workouts, the
 * weighings, the steps and active energy of each day. One idempotent batch:
 * upserted by `externalId`, by `date` for the days, so the same batch sent
 * again changes nothing. One invalid item refuses the whole batch, with its
 * path (`workouts[2].startedAt`), before anything is written.
 */
final class HealthSyncController
{
    public function __construct(
        private readonly Security $security,
        private readonly ValidatorInterface $validator,
        private readonly MessageBusInterface $bus,
    ) {
    }

    #[Route('/api/health/sync', name: 'api_health_sync', methods: ['POST'])]
    public function sync(Request $request): JsonResponse
    {
        $user = $this->security->getUser();
        if (!$user instanceof User) {
            return new JsonResponse(['error' => 'Unauthorized'], Response::HTTP_UNAUTHORIZED);
        }

        try {
            $body = json_decode($request->getContent(), true, 512, \JSON_THROW_ON_ERROR);
        } catch (\JsonException) {
            $body = null;
        }
        if (!\is_array($body) || ([] !== $body && array_is_list($body))) {
            return new JsonResponse(['error' => 'The body must be an object with workouts, measurements and/or days.'], Response::HTTP_BAD_REQUEST);
        }

        $batch = HealthSyncRequest::fromBody($body, $error);
        if (null === $batch) {
            return new JsonResponse(['error' => $error], Response::HTTP_BAD_REQUEST);
        }

        $violations = $this->validator->validate($batch);
        if (\count($violations) > 0) {
            $violation = $violations[0];
            $path = $violation->getPropertyPath();

            return new JsonResponse(['error' => "{$path}: {$violation->getMessage()}", 'path' => $path], Response::HTTP_BAD_REQUEST);
        }

        try {
            $envelope = $this->bus->dispatch(self::command((string) $user->getId(), $batch));
        } catch (HandlerFailedException $e) {
            // A refused value is the caller's; anything else is ours, and not theirs to read.
            // A nested command's failure comes wrapped twice.
            $cause = $e;
            while ($cause instanceof HandlerFailedException && null !== $cause->getPrevious()) {
                $cause = $cause->getPrevious();
            }
            if (!$cause instanceof \DomainException) {
                throw $e;
            }

            return new JsonResponse(['error' => $cause->getMessage()], Response::HTTP_BAD_REQUEST);
        }

        /** @var HealthSyncResult $result */
        $result = $envelope->last(HandledStamp::class)?->getResult();

        return new JsonResponse($result->toArray());
    }

    /** The validated batch, typed: the constraints guarantee every cast. */
    private static function command(string $userId, HealthSyncRequest $batch): SyncHealthDataCommand
    {
        return new SyncHealthDataCommand(
            userId: $userId,
            workouts: array_map(static fn (SyncedWorkout $w): array => [
                'externalId' => (string) $w->externalId,
                'type' => (string) $w->type,
                'title' => null === $w->title ? null : trim((string) $w->title),
                'startedAt' => (string) $w->startedAt,
                'durationMinutes' => (int) $w->durationMinutes,
                'distanceMeters' => null === $w->distanceMeters ? null : (int) $w->distanceMeters,
                'kcal' => null === $w->kcal ? null : (int) $w->kcal,
                'avgHeartRate' => null === $w->avgHeartRate ? null : (int) $w->avgHeartRate,
                'source' => (string) $w->source,
            ], $batch->workouts),
            measurements: array_map(static fn (SyncedMeasurement $m): array => [
                'externalId' => (string) $m->externalId,
                'measuredAt' => (string) $m->measuredAt,
                'weightKg' => null === $m->weightKg ? null : (float) $m->weightKg,
                'bodyFatPercent' => null === $m->bodyFatPercent ? null : (float) $m->bodyFatPercent,
                'source' => (string) $m->source,
            ], $batch->measurements),
            days: array_map(static fn (SyncedDay $d): array => [
                'date' => (string) $d->date,
                'steps' => (int) $d->steps,
                'activeKcal' => (int) $d->activeKcal,
            ], $batch->days),
        );
    }
}
