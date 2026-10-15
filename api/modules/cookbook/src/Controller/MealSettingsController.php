<?php

declare(strict_types=1);

namespace Maggie\Cookbook\Controller;

use Maggie\Cookbook\Message\UpdateMealSettingsCommand;
use Maggie\Cookbook\UseCase\GetMealSettings;
use Maggie\Core\Entity\User;
use Symfony\Bundle\SecurityBundle\Security;
use Symfony\Component\HttpFoundation\JsonResponse;
use Symfony\Component\HttpFoundation\Request;
use Symfony\Component\HttpFoundation\Response;
use Symfony\Component\Messenger\Exception\HandlerFailedException;
use Symfony\Component\Messenger\MessageBusInterface;
use Symfony\Component\Routing\Attribute\Route;

/**
 * The meal settings (MAG-482), created on first read. Written here and not
 * through an API Platform `Patch` on an identifier-less path, which would
 * build a fresh entity (MeOperationContractTest). The PUT carries
 * `defaultGuests`; a body without it is refused rather than read as a reset.
 */
final class MealSettingsController
{
    private const FIELDS = ['defaultGuests'];

    public function __construct(
        private readonly Security $security,
        private readonly GetMealSettings $getMealSettings,
        private readonly MessageBusInterface $bus,
    ) {
    }

    #[Route('/api/meal-settings/me', name: 'api_meal_settings_read', methods: ['GET'])]
    public function read(): JsonResponse
    {
        $user = $this->security->getUser();
        if (!$user instanceof User) {
            return new JsonResponse(['error' => 'Unauthorized'], Response::HTTP_UNAUTHORIZED);
        }

        return new JsonResponse(GetMealSettings::describe($this->getMealSettings->forUser($user)));
    }

    #[Route('/api/meal-settings/me', name: 'api_meal_settings_update', methods: ['PUT'])]
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

        if (!\is_array($body) || !\array_key_exists('defaultGuests', $body)) {
            return new JsonResponse(['error' => 'defaultGuests is required.'], Response::HTTP_BAD_REQUEST);
        }

        $unknown = array_diff(array_keys($body), self::FIELDS);
        if ([] !== $unknown) {
            return new JsonResponse(['error' => 'Unknown field(s): '.implode(', ', $unknown).'. Known fields: '.implode(', ', self::FIELDS)], Response::HTTP_BAD_REQUEST);
        }

        $settings = $this->getMealSettings->forUser($user);

        try {
            $this->bus->dispatch(new UpdateMealSettingsCommand(
                userId: (string) $user->getId(),
                mealSettingsId: (string) $settings->getId(),
                defaultGuests: $body['defaultGuests'],
            ));
        } catch (HandlerFailedException $e) {
            // A refused value is the caller's; anything else is ours, and not theirs to read.
            $cause = $e->getPrevious() ?? $e;
            if (!$cause instanceof \DomainException) {
                throw $e;
            }

            return new JsonResponse(['error' => $cause->getMessage()], Response::HTTP_BAD_REQUEST);
        }

        return new JsonResponse(GetMealSettings::describe($this->getMealSettings->forUser($user)));
    }
}
