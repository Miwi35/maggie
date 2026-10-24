<?php

declare(strict_types=1);

namespace Maggie\MealGrocery\Controller;

use Maggie\Core\Entity\User;
use Maggie\MealGrocery\Entity\ShortageAlert;
use Maggie\MealGrocery\Repository\ShortageAlertRepository;
use Maggie\MealGrocery\Service\ShortageAlerts;
use Symfony\Bundle\SecurityBundle\Security;
use Symfony\Component\HttpFoundation\JsonResponse;
use Symfony\Component\HttpFoundation\Request;
use Symfony\Component\HttpFoundation\Response;
use Symfony\Component\Routing\Attribute\Route;

/**
 * The button of a « Il manque » notification (MAG-570), pressed on the phone
 * or in the admin: « Ajouter à la liste ». Another user's alert is not found;
 * an alert answers once.
 */
final class ShortageAlertController
{
    public function __construct(
        private readonly Security $security,
        private readonly ShortageAlertRepository $repository,
        private readonly ShortageAlerts $shortageAlerts,
    ) {
    }

    #[Route('/api/shortage-alerts/{id}/action', name: 'api_shortage_alert_action', methods: ['POST'])]
    public function act(string $id, Request $request): JsonResponse
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
        $action = \is_array($body) ? ($body['action'] ?? null) : null;
        if (!\is_string($action) || !\in_array($action, ShortageAlert::ACTIONS, true)) {
            return new JsonResponse(['error' => 'action must be one of: '.implode(', ', ShortageAlert::ACTIONS)], Response::HTTP_BAD_REQUEST);
        }

        $alert = $this->repository->findOwned($user, $id);
        if (null === $alert) {
            return new JsonResponse(['error' => 'Shortage alert not found.'], Response::HTTP_NOT_FOUND);
        }

        try {
            return new JsonResponse($this->shortageAlerts->act($alert, $action));
        } catch (\DomainException $e) {
            return new JsonResponse(['error' => $e->getMessage()], Response::HTTP_CONFLICT);
        }
    }
}
