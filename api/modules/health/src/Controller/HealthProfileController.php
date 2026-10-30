<?php

declare(strict_types=1);

namespace Maggie\Health\Controller;

use Maggie\Core\Entity\User;
use Maggie\Health\Message\UpdateHealthProfileCommand;
use Maggie\Health\Service\HealthProfileReader;
use Symfony\Bundle\SecurityBundle\Security;
use Symfony\Component\HttpFoundation\JsonResponse;
use Symfony\Component\HttpFoundation\Request;
use Symfony\Component\HttpFoundation\Response;
use Symfony\Component\Messenger\Exception\HandlerFailedException;
use Symfony\Component\Messenger\MessageBusInterface;
use Symfony\Component\Routing\Attribute\Route;

/**
 * The health profile (MAG-707): read with its defaults before it is ever
 * written, laid down on first write. Written here and not through an API
 * Platform `Patch` on an identifier-less path, which would build a fresh
 * entity (MeOperationContractTest). The PATCH applies the fields it is sent
 * and only those; a `weightKg` records a manual weighing.
 */
final class HealthProfileController
{
    private const FIELDS = ['sex', 'birthYear', 'heightCm', 'weightKg', 'activityLevel', 'mode', 'enteredNeedKcal', 'stepGoal', 'suggestionWindowStart', 'suggestionWindowEnd', 'sessionMinutes', 'stepSource'];

    public function __construct(
        private readonly Security $security,
        private readonly HealthProfileReader $reader,
        private readonly MessageBusInterface $bus,
    ) {
    }

    #[Route('/api/health/profile', name: 'api_health_profile_read', methods: ['GET'])]
    public function read(): JsonResponse
    {
        $user = $this->security->getUser();
        if (!$user instanceof User) {
            return new JsonResponse(['error' => 'Unauthorized'], Response::HTTP_UNAUTHORIZED);
        }

        return new JsonResponse($this->reader->describe($user));
    }

    #[Route('/api/health/profile', name: 'api_health_profile_update', methods: ['PATCH'])]
    public function update(Request $request): JsonResponse
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

        if (!\is_array($body) || [] === array_intersect_key($body, array_flip(self::FIELDS))) {
            return new JsonResponse(['error' => 'Provide at least one of: '.implode(', ', self::FIELDS)], Response::HTTP_BAD_REQUEST);
        }

        $unknown = array_diff(array_keys($body), self::FIELDS);
        if ([] !== $unknown) {
            return new JsonResponse(['error' => 'Unknown field(s): '.implode(', ', $unknown).'. Known fields: '.implode(', ', self::FIELDS)], Response::HTTP_BAD_REQUEST);
        }

        try {
            $this->bus->dispatch(new UpdateHealthProfileCommand(
                userId: (string) $user->getId(),
                sex: $body['sex'] ?? null,
                birthYear: $body['birthYear'] ?? null,
                heightCm: $body['heightCm'] ?? null,
                weightKg: $body['weightKg'] ?? null,
                activityLevel: $body['activityLevel'] ?? null,
                mode: $body['mode'] ?? null,
                enteredNeedKcal: $body['enteredNeedKcal'] ?? null,
                stepGoal: $body['stepGoal'] ?? null,
                suggestionWindowStart: $body['suggestionWindowStart'] ?? null,
                suggestionWindowEnd: $body['suggestionWindowEnd'] ?? null,
                sessionMinutes: $body['sessionMinutes'] ?? null,
                stepSource: $body['stepSource'] ?? null,
            ));
        } catch (HandlerFailedException $e) {
            // A refused value is the caller's; anything else is ours, and not theirs to read.
            $cause = $e->getPrevious() ?? $e;
            if (!$cause instanceof \DomainException) {
                throw $e;
            }

            return new JsonResponse(['error' => $cause->getMessage()], Response::HTTP_BAD_REQUEST);
        }

        return new JsonResponse($this->reader->describe($user));
    }
}
