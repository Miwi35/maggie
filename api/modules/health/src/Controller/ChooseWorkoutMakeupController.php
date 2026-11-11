<?php

declare(strict_types=1);

namespace Maggie\Health\Controller;

use Maggie\Core\Entity\User;
use Maggie\Health\Entity\WorkoutMakeup;
use Maggie\Health\Enum\MakeupOption;
use Maggie\Health\Exception\InvalidWorkoutMakeupException;
use Maggie\Health\Exception\WorkoutMakeupConflictException;
use Maggie\Health\Exception\WorkoutMakeupNotFoundException;
use Maggie\Health\Message\ChooseWorkoutMakeupCommand;
use Symfony\Bundle\SecurityBundle\Security;
use Symfony\Component\HttpFoundation\JsonResponse;
use Symfony\Component\HttpFoundation\Request;
use Symfony\Component\HttpFoundation\Response;
use Symfony\Component\Messenger\Exception\HandlerFailedException;
use Symfony\Component\Messenger\MessageBusInterface;
use Symfony\Component\Messenger\Stamp\HandledStamp;
use Symfony\Component\Routing\Attribute\Route;
use Symfony\Component\Serializer\Normalizer\NormalizerInterface;

/**
 * The buttons of « Séance manquée » (MAG-771), pressed on the phone or in the
 * admin: softer, shorter or tomorrow. Answers the proposal as `GET
 * /api/workout-makeups/{id}` reads it. Another user's proposal is not found;
 * a proposal answers once (409).
 */
final class ChooseWorkoutMakeupController
{
    public function __construct(
        private readonly Security $security,
        private readonly MessageBusInterface $bus,
        private readonly NormalizerInterface $normalizer,
    ) {
    }

    #[Route('/api/workout-makeups/{id}/choose', name: 'api_health_workout_makeup_choose', methods: ['POST'])]
    public function choose(string $id, Request $request): JsonResponse
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
        $option = \is_array($body) ? ($body['option'] ?? null) : null;
        if (!\is_string($option) || null === MakeupOption::tryFrom($option)) {
            return new JsonResponse(['error' => (new InvalidWorkoutMakeupException())->getMessage(), 'field' => 'option'], Response::HTTP_BAD_REQUEST);
        }

        try {
            $makeup = $this->bus->dispatch(new ChooseWorkoutMakeupCommand((string) $user->getId(), $id, $option))->last(HandledStamp::class)?->getResult();
        } catch (HandlerFailedException $e) {
            $cause = $e->getPrevious() ?? $e;

            return match (true) {
                $cause instanceof WorkoutMakeupNotFoundException => new JsonResponse(['error' => $cause->getMessage()], Response::HTTP_NOT_FOUND),
                $cause instanceof WorkoutMakeupConflictException => new JsonResponse(['error' => $cause->getMessage()], Response::HTTP_CONFLICT),
                $cause instanceof InvalidWorkoutMakeupException => new JsonResponse(['error' => $cause->getMessage(), 'field' => $cause->field], Response::HTTP_BAD_REQUEST),
                default => throw $e,
            };
        }
        \assert($makeup instanceof WorkoutMakeup);

        return new JsonResponse($this->normalizer->normalize($makeup, 'jsonld', ['resource_class' => WorkoutMakeup::class]));
    }
}
