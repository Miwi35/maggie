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

        foreach (['theme', 'locale', 'timezone', 'defaultCalendarView', 'defaultCity'] as $name) {
            if (\array_key_exists($name, $body) && !\is_string($body[$name])) {
                return new JsonResponse(['error' => "{$name} must be a string"], Response::HTTP_BAD_REQUEST);
            }
        }

        if (isset($body['defaultCity']) && mb_strlen(trim($body['defaultCity'])) > 100) {
            return new JsonResponse(
                ['error' => 'defaultCity must be at most 100 characters'],
                Response::HTTP_BAD_REQUEST,
            );
        }

        foreach (['notificationsEnabled', 'morningDigestEnabled', 'eveningReviewEnabled'] as $name) {
            if (\array_key_exists($name, $body) && !\is_bool($body[$name])) {
                return new JsonResponse(['error' => "{$name} must be a boolean"], Response::HTTP_BAD_REQUEST);
            }
        }

        // The daily rendezvous' hours (MAG-787): "HH:MM", never null.
        foreach (['morningDigestTime', 'eveningReviewTime', 'workStartTime', 'workEndTime'] as $name) {
            if (\array_key_exists($name, $body) && !self::isHourMinute($body[$name])) {
                return new JsonResponse(['error' => "{$name} must be HH:MM"], Response::HTTP_BAD_REQUEST);
            }
        }

        // Quiet hours (MAG-687): both "HH:MM", or both null to clear. Sent together, never half.
        $hasStart = \array_key_exists('quietHoursStart', $body);
        $hasEnd = \array_key_exists('quietHoursEnd', $body);
        if ($hasStart || $hasEnd) {
            $start = $body['quietHoursStart'] ?? null;
            $end = $body['quietHoursEnd'] ?? null;
            if (!$hasStart || !$hasEnd || !((self::isHourMinute($start) && self::isHourMinute($end)) || (null === $start && null === $end))) {
                return new JsonResponse(
                    ['error' => 'quietHoursStart and quietHoursEnd must be sent together, both as HH:MM or both null'],
                    Response::HTTP_BAD_REQUEST,
                );
            }
        }

        // Working days (MAG-790): ISO 1 = Monday … 7 = Sunday, each once.
        $workDays = null;
        if (\array_key_exists('workDays', $body)) {
            $days = $body['workDays'];
            if (!\is_array($days) || !array_is_list($days)
                || array_filter($days, static fn (mixed $d): bool => \is_int($d) && $d >= 1 && $d <= 7) !== $days) {
                return new JsonResponse(['error' => 'workDays must be a list of ISO days, 1 to 7'], Response::HTTP_BAD_REQUEST);
            }
            $workDays = array_values(array_unique($days));
            sort($workDays);
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
                defaultCity: $body['defaultCity'] ?? null,
                quietHoursStart: $hasStart ? ($body['quietHoursStart'] ?? '') : null,
                quietHoursEnd: $hasEnd ? ($body['quietHoursEnd'] ?? '') : null,
                morningDigestEnabled: $body['morningDigestEnabled'] ?? null,
                morningDigestTime: $body['morningDigestTime'] ?? null,
                eveningReviewEnabled: $body['eveningReviewEnabled'] ?? null,
                eveningReviewTime: $body['eveningReviewTime'] ?? null,
                workStartTime: $body['workStartTime'] ?? null,
                workEndTime: $body['workEndTime'] ?? null,
                workDays: $workDays,
            ));
        } catch (HandlerFailedException $e) {
            $cause = $e->getPrevious() ?? $e;

            return new JsonResponse(['error' => $cause->getMessage()], Response::HTTP_BAD_REQUEST);
        }

        $this->em->refresh($preference);

        $response = [
            'id' => (string) $preference->getId(),
            'theme' => $preference->getTheme(),
            'locale' => $preference->getLocale(),
            'timezone' => $preference->getTimezone(),
            'defaultCalendarView' => $preference->getDefaultCalendarView(),
            'enabledAgendaIds' => $preference->getEnabledAgendaIds(),
            'notificationsEnabled' => $preference->isNotificationsEnabled(),
            'morningDigestEnabled' => $preference->getMorningDigestEnabled(),
            'morningDigestTime' => $preference->getMorningDigestTime(),
            'eveningReviewEnabled' => $preference->getEveningReviewEnabled(),
            'eveningReviewTime' => $preference->getEveningReviewTime(),
            'workStartTime' => $preference->getWorkStartTime(),
            'workEndTime' => $preference->getWorkEndTime(),
            'workDays' => $preference->getWorkDays(),
        ];
        // Like the GET, which API Platform serialises without its null fields:
        // the two answers must carry the same keys.
        if (null !== $preference->getDefaultCity()) {
            $response['defaultCity'] = $preference->getDefaultCity();
        }

        if (null !== $preference->getQuietHoursStart()) {
            $response['quietHoursStart'] = $preference->getQuietHoursStart();
            $response['quietHoursEnd'] = $preference->getQuietHoursEnd();
        }

        return new JsonResponse($response);
    }

    private static function isHourMinute(mixed $value): bool
    {
        return \is_string($value) && 1 === preg_match('/^([01]\d|2[0-3]):[0-5]\d$/', $value);
    }

    private function createFor(User $user): UserPreference
    {
        $preference = (new UserPreference())->setUser($user);
        $this->em->persist($preference);
        $this->em->flush();

        return $preference;
    }
}
