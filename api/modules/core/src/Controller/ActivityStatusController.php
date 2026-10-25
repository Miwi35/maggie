<?php

declare(strict_types=1);

namespace Maggie\Core\Controller;

use Doctrine\DBAL\Exception\UniqueConstraintViolationException;
use Maggie\Core\Entity\ActivityStatus;
use Maggie\Core\Entity\User;
use Maggie\Core\Enum\Activity;
use Maggie\Core\Message\DeleteActivityStatusCommand;
use Maggie\Core\Message\SaveActivityStatusCommand;
use Maggie\Core\Repository\ActivityStatusRepository;
use Symfony\Bundle\SecurityBundle\Security;
use Symfony\Component\HttpFoundation\JsonResponse;
use Symfony\Component\HttpFoundation\Request;
use Symfony\Component\HttpFoundation\Response;
use Symfony\Component\Messenger\Exception\HandlerFailedException;
use Symfony\Component\Messenger\MessageBusInterface;
use Symfony\Component\Messenger\Stamp\HandledStamp;
use Symfony\Component\Routing\Attribute\Route;

/**
 * The activities in progress of the current user (MAG-613), so one left on a
 * device resumes there on another. Hand-written: the item is addressed by its
 * activity, not by an id, and an API Platform write on such a path builds a
 * fresh object instead of the stored one.
 */
final class ActivityStatusController
{
    private const ORIGIN_ID_MAX = 64;

    public function __construct(
        private readonly Security $security,
        private readonly ActivityStatusRepository $repository,
        private readonly MessageBusInterface $bus,
    ) {
    }

    /** `?activity=cuisine` keeps that one. */
    #[Route('/api/activity-status', name: 'api_activity_status_list', methods: ['GET'])]
    public function list(Request $request): JsonResponse
    {
        $user = $this->security->getUser();
        if (!$user instanceof User) {
            return self::unauthorized();
        }

        $activity = null;
        if ($request->query->has('activity')) {
            $activity = Activity::tryFrom($request->query->getString('activity'));
            if (null === $activity) {
                return self::unknownActivity();
            }
        }

        return new JsonResponse(['member' => array_map(
            static fn (ActivityStatus $status): array => $status->describe(),
            $this->repository->findByUser($user, $activity),
        )]);
    }

    #[Route('/api/activity-status/{activity}', name: 'api_activity_status_read', methods: ['GET'])]
    public function read(string $activity): JsonResponse
    {
        $user = $this->security->getUser();
        if (!$user instanceof User) {
            return self::unauthorized();
        }

        $known = Activity::tryFrom($activity);
        if (null === $known) {
            return self::unknownActivity();
        }

        $status = $this->repository->findOneByUserAndActivity($user, $known);
        if (null === $status) {
            return new JsonResponse(['error' => 'No activity in progress.'], Response::HTTP_NOT_FOUND);
        }

        return new JsonResponse($status->describe());
    }

    /** Body: `origin` {kind, id} and `state`, a JSON object. Creates (201) or replaces (200). */
    #[Route('/api/activity-status/{activity}', name: 'api_activity_status_save', methods: ['PUT'])]
    public function save(string $activity, Request $request): JsonResponse
    {
        $user = $this->security->getUser();
        if (!$user instanceof User) {
            return self::unauthorized();
        }

        $known = Activity::tryFrom($activity);
        if (null === $known) {
            return self::unknownActivity();
        }

        // Decoded to objects: an array decode cannot tell `{}` from `[]`.
        try {
            $body = json_decode($request->getContent(), false, 512, \JSON_THROW_ON_ERROR);
        } catch (\JsonException) {
            $body = null;
        }
        if (!$body instanceof \stdClass) {
            return self::badRequest('Send a JSON object with origin and state.');
        }

        $origin = $body->origin ?? null;
        $kind = $origin instanceof \stdClass ? ($origin->kind ?? null) : null;
        $id = $origin instanceof \stdClass ? ($origin->id ?? null) : null;
        if (!\is_string($kind) || !\in_array($kind, $known->originKinds(), true)) {
            return self::badRequest(\sprintf('origin.kind must be one of %s.', implode(', ', $known->originKinds())));
        }
        if (!\is_string($id) || '' === trim($id) || mb_strlen($id) > self::ORIGIN_ID_MAX) {
            return self::badRequest(\sprintf('origin.id must be a non-empty string of at most %d characters.', self::ORIGIN_ID_MAX));
        }

        $state = $body->state ?? null;
        if (!$state instanceof \stdClass) {
            return self::badRequest('state must be a JSON object.');
        }
        $encoded = json_encode($state, \JSON_THROW_ON_ERROR | \JSON_UNESCAPED_SLASHES | \JSON_UNESCAPED_UNICODE | \JSON_PRESERVE_ZERO_FRACTION);
        if (\strlen($encoded) > ActivityStatus::MAX_STATE_BYTES) {
            return self::badRequest(\sprintf('state must be at most %d bytes of JSON.', ActivityStatus::MAX_STATE_BYTES));
        }

        $existed = null !== $this->repository->findOneByUserAndActivity($user, $known);

        try {
            $status = $this->bus->dispatch(new SaveActivityStatusCommand(
                userId: (string) $user->getId(),
                activity: $known,
                originKind: $kind,
                originId: $id,
                state: $encoded,
            ))->last(HandledStamp::class)?->getResult();
        } catch (HandlerFailedException $e) {
            // Two devices laying down the first status at once: the other one won.
            if ($e->getPrevious() instanceof UniqueConstraintViolationException) {
                return new JsonResponse(['error' => 'The activity was started on another device at the same time; read it again.'], Response::HTTP_CONFLICT);
            }

            throw $e;
        }
        \assert($status instanceof ActivityStatus);

        return new JsonResponse($status->describe(), $existed ? Response::HTTP_OK : Response::HTTP_CREATED);
    }

    /** The activity ended, or starts over. Nothing in progress is not an error. */
    #[Route('/api/activity-status/{activity}', name: 'api_activity_status_delete', methods: ['DELETE'])]
    public function delete(string $activity): Response
    {
        $user = $this->security->getUser();
        if (!$user instanceof User) {
            return self::unauthorized();
        }

        $known = Activity::tryFrom($activity);
        if (null === $known) {
            return self::unknownActivity();
        }

        $this->bus->dispatch(new DeleteActivityStatusCommand(userId: (string) $user->getId(), activity: $known));

        return new Response(null, Response::HTTP_NO_CONTENT);
    }

    private static function unauthorized(): JsonResponse
    {
        return new JsonResponse(['error' => 'Unauthorized'], Response::HTTP_UNAUTHORIZED);
    }

    private static function unknownActivity(): JsonResponse
    {
        return self::badRequest(\sprintf('activity must be one of %s.', implode(', ', Activity::values())));
    }

    private static function badRequest(string $error): JsonResponse
    {
        return new JsonResponse(['error' => $error], Response::HTTP_BAD_REQUEST);
    }
}
