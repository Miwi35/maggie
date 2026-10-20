<?php

declare(strict_types=1);

namespace Maggie\Calendar\Controller;

use Maggie\Calendar\Entity\Routine;
use Maggie\Calendar\Message\CheckRoutineCommand;
use Maggie\Calendar\Message\CreateRoutineCommand;
use Maggie\Calendar\Message\DeleteRoutineCommand;
use Maggie\Calendar\Message\UpdateRoutineCommand;
use Maggie\Calendar\Repository\RoutineRepository;
use Maggie\Calendar\Service\DayPlans;
use Maggie\Calendar\Service\RoutineSchedule;
use Maggie\Core\Entity\User;
use Symfony\Bundle\SecurityBundle\Security;
use Symfony\Component\HttpFoundation\JsonResponse;
use Symfony\Component\HttpFoundation\Request;
use Symfony\Component\HttpFoundation\Response;
use Symfony\Component\Messenger\Exception\HandlerFailedException;
use Symfony\Component\Messenger\MessageBusInterface;
use Symfony\Component\Messenger\Stamp\HandledStamp;
use Symfony\Component\Routing\Attribute\Route;

/**
 * Routines (MAG-511): the list with what was done, the day's, and a tick
 * per local date — `PUT` and `DELETE` on `/checks/{date}`, both idempotent.
 * Another user's routine is not found. Each read is {@see RoutineSchedule}'s.
 */
final class RoutineController
{
    public function __construct(
        private readonly Security $security,
        private readonly RoutineRepository $repository,
        private readonly RoutineSchedule $schedule,
        private readonly MessageBusInterface $bus,
    ) {
    }

    #[Route('/api/routines', name: 'api_routines_list', methods: ['GET'])]
    public function list(): JsonResponse
    {
        $user = $this->security->getUser();
        if (!$user instanceof User) {
            return self::unauthorized();
        }

        return new JsonResponse(['member' => $this->schedule->describeAll($user)]);
    }

    #[Route('/api/routines/day/{date}', name: 'api_routines_day', methods: ['GET'], priority: 1)]
    public function day(string $date): JsonResponse
    {
        $user = $this->security->getUser();
        if (!$user instanceof User) {
            return self::unauthorized();
        }

        try {
            $day = DayPlans::day($date);
        } catch (\InvalidArgumentException $e) {
            return new JsonResponse(['error' => $e->getMessage()], Response::HTTP_BAD_REQUEST);
        }

        return new JsonResponse($this->schedule->day($user, $day));
    }

    #[Route('/api/routines', name: 'api_routines_create', methods: ['POST'])]
    public function create(Request $request): JsonResponse
    {
        $user = $this->security->getUser();
        if (!$user instanceof User) {
            return self::unauthorized();
        }

        $fields = self::body($request);
        if (null === $fields) {
            return new JsonResponse(['error' => 'Provide a JSON object: title, anchor, anchorTime, recurrence, active, remind.'], Response::HTTP_BAD_REQUEST);
        }

        $routine = $this->handle(new CreateRoutineCommand((string) $user->getId(), $fields));
        if ($routine instanceof JsonResponse) {
            return $routine;
        }
        \assert($routine instanceof Routine);

        return new JsonResponse($this->schedule->describe($routine), Response::HTTP_CREATED);
    }

    #[Route('/api/routines/{id}', name: 'api_routines_update', methods: ['PATCH'])]
    public function update(Request $request, string $id): JsonResponse
    {
        $user = $this->security->getUser();
        if (!$user instanceof User) {
            return self::unauthorized();
        }
        $routine = $this->repository->findOwned($user, $id);
        if (null === $routine) {
            return self::notFound();
        }

        $fields = self::body($request);
        if (null === $fields) {
            return new JsonResponse(['error' => 'Provide a JSON object with the fields to change.'], Response::HTTP_BAD_REQUEST);
        }

        $result = $this->handle(new UpdateRoutineCommand((string) $user->getId(), $id, $fields));
        if ($result instanceof JsonResponse) {
            return $result;
        }

        return new JsonResponse($this->schedule->describe($routine));
    }

    #[Route('/api/routines/{id}', name: 'api_routines_delete', methods: ['DELETE'])]
    public function delete(string $id): Response
    {
        $user = $this->security->getUser();
        if (!$user instanceof User) {
            return self::unauthorized();
        }
        if (null === $this->repository->findOwned($user, $id)) {
            return self::notFound();
        }

        $this->bus->dispatch(new DeleteRoutineCommand((string) $user->getId(), $id));

        return new Response(null, Response::HTTP_NO_CONTENT);
    }

    #[Route('/api/routines/{id}/checks/{date}', name: 'api_routines_check', methods: ['PUT', 'DELETE'])]
    public function check(Request $request, string $id, string $date): JsonResponse
    {
        $user = $this->security->getUser();
        if (!$user instanceof User) {
            return self::unauthorized();
        }
        $routine = $this->repository->findOwned($user, $id);
        if (null === $routine) {
            return self::notFound();
        }

        try {
            $day = DayPlans::day($date);
        } catch (\InvalidArgumentException $e) {
            return new JsonResponse(['error' => $e->getMessage()], Response::HTTP_BAD_REQUEST);
        }

        $result = $this->handle(new CheckRoutineCommand((string) $user->getId(), $id, $day, $request->isMethod('PUT')));
        if ($result instanceof JsonResponse) {
            return $result;
        }

        return new JsonResponse($this->schedule->describe($routine));
    }

    /** The handler's result, or the 400 of a refusal; anything else is ours, and not the caller's to read. */
    private function handle(object $command): mixed
    {
        try {
            return $this->bus->dispatch($command)->last(HandledStamp::class)?->getResult();
        } catch (HandlerFailedException $e) {
            $cause = $e->getPrevious() ?? $e;
            if (!$cause instanceof \DomainException) {
                throw $e;
            }

            return new JsonResponse(['error' => $cause->getMessage()], Response::HTTP_BAD_REQUEST);
        }
    }

    /** @return array<string, mixed>|null */
    private static function body(Request $request): ?array
    {
        try {
            $body = json_decode($request->getContent(), true, 512, \JSON_THROW_ON_ERROR);
        } catch (\JsonException) {
            return null;
        }

        return \is_array($body) && ([] === $body || !array_is_list($body)) ? $body : null;
    }

    private static function unauthorized(): JsonResponse
    {
        return new JsonResponse(['error' => 'Unauthorized'], Response::HTTP_UNAUTHORIZED);
    }

    private static function notFound(): JsonResponse
    {
        return new JsonResponse(['error' => 'Routine not found.'], Response::HTTP_NOT_FOUND);
    }
}
