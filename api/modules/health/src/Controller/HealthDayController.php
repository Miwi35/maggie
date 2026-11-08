<?php

declare(strict_types=1);

namespace Maggie\Health\Controller;

use Maggie\Core\Entity\User;
use Maggie\Health\Enum\HealthDayAuthor;
use Maggie\Health\Message\UpdateHealthDayCommand;
use Maggie\Health\Service\HealthDayReader;
use Symfony\Bundle\SecurityBundle\Security;
use Symfony\Component\HttpFoundation\JsonResponse;
use Symfony\Component\HttpFoundation\Request;
use Symfony\Component\HttpFoundation\Response;
use Symfony\Component\Messenger\Exception\HandlerFailedException;
use Symfony\Component\Messenger\MessageBusInterface;
use Symfony\Component\Routing\Attribute\Route;

/**
 * The health journal (MAG-750), one entry per local day, known by its date.
 * Written here and not through an API Platform `Patch`: a date the journal
 * does not hold yet lays its entry down, which an item operation would 404.
 * The PATCH applies mood, fatigue and the note it is sent and only those;
 * `null` empties one. The computed columns are never written from outside.
 */
final class HealthDayController
{
    private const FIELDS = ['mood', 'fatigue', 'note'];

    public function __construct(
        private readonly Security $security,
        private readonly HealthDayReader $reader,
        private readonly MessageBusInterface $bus,
    ) {
    }

    #[Route('/api/health/days', name: 'api_health_days_list', methods: ['GET'])]
    public function list(Request $request): JsonResponse
    {
        $user = $this->security->getUser();
        if (!$user instanceof User) {
            return new JsonResponse(['error' => 'Unauthorized'], Response::HTTP_UNAUTHORIZED);
        }

        $from = $request->query->get('from');
        $to = $request->query->get('to');
        if (!\is_string($from) || !\is_string($to)) {
            return new JsonResponse(['error' => 'from and to are required, as YYYY-MM-DD.'], Response::HTTP_BAD_REQUEST);
        }

        try {
            return new JsonResponse(['days' => $this->reader->range($user, $from, $to)]);
        } catch (\InvalidArgumentException $e) {
            return new JsonResponse(['error' => $e->getMessage()], Response::HTTP_BAD_REQUEST);
        }
    }

    #[Route('/api/health/days/{date}', name: 'api_health_days_read', methods: ['GET'])]
    public function read(string $date): JsonResponse
    {
        $user = $this->security->getUser();
        if (!$user instanceof User) {
            return new JsonResponse(['error' => 'Unauthorized'], Response::HTTP_UNAUTHORIZED);
        }

        try {
            return new JsonResponse($this->reader->day($user, $date));
        } catch (\InvalidArgumentException $e) {
            return new JsonResponse(['error' => $e->getMessage()], Response::HTTP_BAD_REQUEST);
        }
    }

    #[Route('/api/health/days/{date}', name: 'api_health_days_update', methods: ['PATCH'])]
    public function update(string $date, Request $request): JsonResponse
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

        if (!\is_array($body) || array_is_list($body) || [] === array_intersect_key($body, array_flip(self::FIELDS))) {
            return new JsonResponse(['error' => 'Provide at least one of: '.implode(', ', self::FIELDS)], Response::HTTP_BAD_REQUEST);
        }

        $unknown = array_diff(array_keys($body), self::FIELDS);
        if ([] !== $unknown) {
            return new JsonResponse(['error' => 'Unknown field(s): '.implode(', ', $unknown).'. Known fields: '.implode(', ', self::FIELDS)], Response::HTTP_BAD_REQUEST);
        }

        try {
            $this->bus->dispatch(new UpdateHealthDayCommand(
                userId: (string) $user->getId(),
                date: $date,
                author: HealthDayAuthor::User,
                mood: $body['mood'] ?? null,
                fatigue: $body['fatigue'] ?? null,
                note: $body['note'] ?? null,
                cleared: array_map('strval', array_keys(array_filter($body, static fn (mixed $value): bool => null === $value))),
            ));
        } catch (HandlerFailedException $e) {
            // A refused value is the caller's; anything else is ours, and not theirs to read.
            $cause = $e->getPrevious() ?? $e;
            if (!$cause instanceof \DomainException) {
                throw $e;
            }

            return new JsonResponse(['error' => $cause->getMessage()], Response::HTTP_BAD_REQUEST);
        }

        return new JsonResponse($this->reader->day($user, $date));
    }
}
