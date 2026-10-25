<?php

declare(strict_types=1);

namespace Maggie\Cookbook\Controller;

use Maggie\Cookbook\Entity\CookingTimer;
use Maggie\Cookbook\Enum\CookingTimerAction;
use Maggie\Cookbook\Enum\CookingTimerStatus;
use Maggie\Cookbook\Exception\CookingTimerNotFoundException;
use Maggie\Cookbook\Message\ChangeCookingTimerCommand;
use Maggie\Cookbook\Message\StartCookingTimerCommand;
use Maggie\Cookbook\Repository\CookingTimerRepository;
use Maggie\Core\Entity\User;
use Symfony\Bundle\SecurityBundle\Security;
use Symfony\Component\Clock\ClockInterface;
use Symfony\Component\HttpFoundation\JsonResponse;
use Symfony\Component\HttpFoundation\Request;
use Symfony\Component\HttpFoundation\Response;
use Symfony\Component\Messenger\Exception\HandlerFailedException;
use Symfony\Component\Messenger\MessageBusInterface;
use Symfony\Component\Messenger\Stamp\HandledStamp;
use Symfony\Component\Routing\Attribute\Route;

/**
 * The timers of the Cuisine activity (MAG-582), the user's own only.
 *
 *     POST  /api/cooking/timers        {label, recipe, stepPosition, durationSeconds}
 *     PATCH /api/cooking/timers/{id}   {action: pause|resume|add|cancel|dismiss, seconds?}
 *     GET   /api/cooking/timers?status=running,paused,done
 *
 * A controller and not API Platform: a change is an action, not fields. Every
 * answer is the timer as {@see CookingTimer::describe()} spells it; `endsAt` is
 * the authority a reload or a rotation reads back.
 */
final class CookingTimerController
{
    private const START_FIELDS = ['label', 'recipe', 'stepPosition', 'durationSeconds'];
    private const CHANGE_FIELDS = ['action', 'seconds'];

    public function __construct(
        private readonly Security $security,
        private readonly CookingTimerRepository $timers,
        private readonly MessageBusInterface $bus,
        private readonly ClockInterface $clock,
    ) {
    }

    #[Route('/api/cooking/timers', name: 'api_cooking_timers_list', methods: ['GET'])]
    public function list(Request $request): JsonResponse
    {
        $user = $this->security->getUser();
        if (!$user instanceof User) {
            return self::unauthorized();
        }

        $statuses = [];
        $asked = $request->query->get('status');
        foreach (null === $asked || '' === $asked ? ['running', 'paused', 'done'] : explode(',', (string) $asked) as $value) {
            $status = CookingTimerStatus::tryFrom(trim($value));
            if (null === $status) {
                return $this->badRequest(\sprintf('Unknown status: %s. Known statuses: %s.', $value, implode(', ', array_map(static fn (CookingTimerStatus $s) => $s->value, CookingTimerStatus::cases()))));
            }
            $statuses[] = $status;
        }

        $now = $this->clock->now();
        $member = array_map(static fn (CookingTimer $timer) => $timer->describe($now), $this->timers->findByUserAndStatuses($user, $statuses));

        return new JsonResponse(['member' => $member, 'totalItems' => \count($member)]);
    }

    #[Route('/api/cooking/timers', name: 'api_cooking_timers_start', methods: ['POST'])]
    public function start(Request $request): JsonResponse
    {
        $user = $this->security->getUser();
        if (!$user instanceof User) {
            return self::unauthorized();
        }

        $body = self::body($request);
        if (null === $body) {
            return $this->badRequest('The body must be a JSON object.');
        }
        if (null !== $refused = $this->unknownFields($body, self::START_FIELDS)) {
            return $refused;
        }
        foreach (['label' => 'string', 'recipe' => 'string', 'stepPosition' => 'int', 'durationSeconds' => 'int'] as $field => $type) {
            $value = $body[$field] ?? null;
            if (('string' === $type && !\is_string($value)) || ('int' === $type && !\is_int($value))) {
                return $this->badRequest(\sprintf('%s is required, %s.', $field, 'string' === $type ? 'a string' : 'a whole number'));
            }
        }

        return $this->dispatch(new StartCookingTimerCommand(
            userId: (string) $user->getId(),
            label: $body['label'],
            recipeId: $body['recipe'],
            stepPosition: $body['stepPosition'],
            durationSeconds: $body['durationSeconds'],
        ), Response::HTTP_CREATED);
    }

    #[Route('/api/cooking/timers/{id}', name: 'api_cooking_timers_change', methods: ['PATCH'])]
    public function change(string $id, Request $request): JsonResponse
    {
        $user = $this->security->getUser();
        if (!$user instanceof User) {
            return self::unauthorized();
        }

        // Another user's timer, or none: the same 404, before the body is even read.
        $timer = $this->timers->findOneForUser($id, $user);
        if (null === $timer) {
            return new JsonResponse(['error' => \sprintf('Cooking timer not found: %s', $id)], Response::HTTP_NOT_FOUND);
        }

        $body = self::body($request);
        if (null === $body) {
            return $this->badRequest('The body must be a JSON object.', $timer);
        }
        if (null !== $refused = $this->unknownFields($body, self::CHANGE_FIELDS, $timer)) {
            return $refused;
        }
        $action = \is_string($body['action'] ?? null) ? CookingTimerAction::tryFrom($body['action']) : null;
        if (null === $action) {
            return $this->badRequest(\sprintf('action is required, one of: %s.', implode(', ', CookingTimerAction::values())), $timer);
        }
        $seconds = $body['seconds'] ?? null;
        if (null !== $seconds && (!\is_int($seconds) || CookingTimerAction::Add !== $action)) {
            return $this->badRequest('seconds is a whole number, and only for add.', $timer);
        }

        return $this->dispatch(new ChangeCookingTimerCommand(
            userId: (string) $user->getId(),
            timerId: (string) $timer->getId(),
            action: $action,
            seconds: $seconds,
        ), Response::HTTP_OK, $timer);
    }

    private function dispatch(object $command, int $status, ?CookingTimer $timer = null): JsonResponse
    {
        try {
            $envelope = $this->bus->dispatch($command);
        } catch (HandlerFailedException $e) {
            // A refused value is the caller's; anything else is ours, and not theirs to read.
            $cause = $e->getPrevious() ?? $e;
            if ($cause instanceof CookingTimerNotFoundException) {
                return new JsonResponse(['error' => $cause->getMessage()], Response::HTTP_NOT_FOUND);
            }
            if (!$cause instanceof \DomainException) {
                throw $e;
            }

            return $this->badRequest($cause->getMessage(), $timer);
        }

        $result = $envelope->last(HandledStamp::class)?->getResult();
        \assert($result instanceof CookingTimer);

        return new JsonResponse($result->describe($this->clock->now()), $status);
    }

    /** @return array<string, mixed>|null */
    private static function body(Request $request): ?array
    {
        try {
            $body = json_decode($request->getContent(), true, 512, \JSON_THROW_ON_ERROR);
        } catch (\JsonException) {
            return null;
        }

        // `{}` decodes to an empty list: an object without fields, not a list.
        return \is_array($body) && ([] === $body || !array_is_list($body)) ? $body : null;
    }

    /**
     * @param array<string, mixed> $body
     * @param list<string>         $known
     */
    private function unknownFields(array $body, array $known, ?CookingTimer $timer = null): ?JsonResponse
    {
        $unknown = array_diff(array_keys($body), $known);

        return [] === $unknown ? null : $this->badRequest('Unknown field(s): '.implode(', ', $unknown).'. Known fields: '.implode(', ', $known), $timer);
    }

    /** A refused change answers the timer as it stands, so the caller sees what it can do with it. */
    private function badRequest(string $error, ?CookingTimer $timer = null): JsonResponse
    {
        $body = ['error' => $error];
        if (null !== $timer) {
            $body['timer'] = $timer->describe($this->clock->now());
        }

        return new JsonResponse($body, Response::HTTP_BAD_REQUEST);
    }

    private static function unauthorized(): JsonResponse
    {
        return new JsonResponse(['error' => 'Unauthorized'], Response::HTTP_UNAUTHORIZED);
    }
}
