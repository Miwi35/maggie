<?php

declare(strict_types=1);

namespace Maggie\Core\Controller;

use Doctrine\ORM\EntityManagerInterface;
use Maggie\Core\Entity\User;
use Maggie\Core\Entity\UserPreference;
use Maggie\Core\Message\UpdateUserPreferenceCommand;
use Maggie\Core\Repository\UserPreferenceRepository;
use Symfony\Bundle\SecurityBundle\Security;
use Symfony\Component\HttpFoundation\JsonResponse;
use Symfony\Component\HttpFoundation\Request;
use Symfony\Component\HttpFoundation\Response;
use Symfony\Component\Messenger\Exception\HandlerFailedException;
use Symfony\Component\Messenger\MessageBusInterface;
use Symfony\Component\Routing\Attribute\Route;

/**
 * Hand-written because an API Platform PATCH on an identifier-less operation
 * (/user_preferences/me) does not populate the object returned by the
 * provider — it builds a fresh one, whose id matches no row, and the handler
 * then fails to find it.
 */
final class UpdateUserPreferenceController
{
    public function __construct(
        private readonly Security $security,
        private readonly UserPreferenceRepository $repository,
        private readonly EntityManagerInterface $em,
        private readonly MessageBusInterface $bus,
    ) {
    }

    #[Route('/api/user_preferences/me', name: 'api_user_preference_update', methods: ['PATCH'])]
    public function __invoke(Request $request): JsonResponse
    {
        $user = $this->security->getUser();
        if (!$user instanceof User) {
            return new JsonResponse(['error' => 'Unauthorized'], Response::HTTP_UNAUTHORIZED);
        }

        $content = $request->getContent();
        $body = '' === $content ? [] : json_decode($content, true, 512, \JSON_THROW_ON_ERROR);

        if (!\is_array($body)) {
            return new JsonResponse(['error' => 'A JSON object is expected'], Response::HTTP_BAD_REQUEST);
        }

        foreach (['theme', 'locale', 'timezone', 'defaultCalendarView'] as $name) {
            if (\array_key_exists($name, $body) && !\is_string($body[$name])) {
                return new JsonResponse(['error' => "{$name} must be a string"], Response::HTTP_BAD_REQUEST);
            }
        }

        if (\array_key_exists('notificationsEnabled', $body) && !\is_bool($body['notificationsEnabled'])) {
            return new JsonResponse(
                ['error' => 'notificationsEnabled must be a boolean'],
                Response::HTTP_BAD_REQUEST,
            );
        }

        $agendaIds = $body['enabledAgendaIds'] ?? null;
        if (null !== $agendaIds && (!\is_array($agendaIds) || array_filter($agendaIds, 'is_string') !== $agendaIds)) {
            return new JsonResponse(
                ['error' => 'enabledAgendaIds must be an array of strings'],
                Response::HTTP_BAD_REQUEST,
            );
        }

        $preference = $this->repository->findOneByUser($user) ?? $this->createFor($user);

        try {
            // Only the keys actually sent are passed on: anything left out keeps
            // the value it already had.
            $this->bus->dispatch(new UpdateUserPreferenceCommand(
                userPreferenceId: (string) $preference->getId(),
                theme: $body['theme'] ?? null,
                locale: $body['locale'] ?? null,
                timezone: $body['timezone'] ?? null,
                defaultCalendarView: $body['defaultCalendarView'] ?? null,
                enabledAgendaIds: null === $agendaIds ? null : array_values($agendaIds),
                notificationsEnabled: $body['notificationsEnabled'] ?? null,
            ));
        } catch (HandlerFailedException $e) {
            $cause = $e->getPrevious() ?? $e;

            return new JsonResponse(['error' => $cause->getMessage()], Response::HTTP_BAD_REQUEST);
        }

        $this->em->refresh($preference);

        return new JsonResponse([
            'id' => (string) $preference->getId(),
            'theme' => $preference->getTheme(),
            'locale' => $preference->getLocale(),
            'timezone' => $preference->getTimezone(),
            'defaultCalendarView' => $preference->getDefaultCalendarView(),
            'enabledAgendaIds' => $preference->getEnabledAgendaIds(),
            'notificationsEnabled' => $preference->isNotificationsEnabled(),
        ]);
    }

    private function createFor(User $user): UserPreference
    {
        $preference = (new UserPreference())->setUser($user);
        $this->em->persist($preference);
        $this->em->flush();

        return $preference;
    }
}
