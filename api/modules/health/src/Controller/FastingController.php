<?php

declare(strict_types=1);

namespace Maggie\Health\Controller;

use Maggie\Core\Entity\User;
use Maggie\Health\Service\DailyEnergyReader;
use Maggie\Health\Service\FastingReader;
use Psr\Clock\ClockInterface;
use Symfony\Bundle\SecurityBundle\Security;
use Symfony\Component\HttpFoundation\JsonResponse;
use Symfony\Component\HttpFoundation\Request;
use Symfony\Component\HttpFoundation\Response;
use Symfony\Component\Routing\Attribute\Route;

/**
 * A day's intermittent fast (MAG-770): `GET /api/health/fasting?date=`, today
 * when no date is given. The window comes from the profile, the rest from the
 * food journal ({@see FastingReader}).
 */
final class FastingController
{
    public function __construct(
        private readonly Security $security,
        private readonly FastingReader $reader,
        private readonly ClockInterface $clock,
    ) {
    }

    #[Route('/api/health/fasting', name: 'api_health_fasting', methods: ['GET'])]
    public function read(Request $request): JsonResponse
    {
        $user = $this->security->getUser();
        if (!$user instanceof User) {
            return new JsonResponse(['error' => 'Unauthorized'], Response::HTTP_UNAUTHORIZED);
        }

        $date = $request->query->get('date') ?? $this->clock->now()->setTimezone(new \DateTimeZone(DailyEnergyReader::TIME_ZONE))->format('Y-m-d');

        try {
            return new JsonResponse($this->reader->day($user, (string) $date));
        } catch (\InvalidArgumentException $e) {
            return new JsonResponse(['error' => $e->getMessage()], Response::HTTP_BAD_REQUEST);
        }
    }
}
