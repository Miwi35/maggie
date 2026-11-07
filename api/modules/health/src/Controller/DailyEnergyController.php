<?php

declare(strict_types=1);

namespace Maggie\Health\Controller;

use Maggie\Core\Entity\User;
use Maggie\Health\Service\DailyEnergyReader;
use Symfony\Bundle\SecurityBundle\Security;
use Symfony\Component\HttpFoundation\JsonResponse;
use Symfony\Component\HttpFoundation\Request;
use Symfony\Component\HttpFoundation\Response;
use Symfony\Component\Routing\Attribute\Route;

/** The consolidated daily energy (MAG-746): `GET /api/health/daily-energy?from=&to=`, one row per local day. */
final class DailyEnergyController
{
    public function __construct(
        private readonly Security $security,
        private readonly DailyEnergyReader $reader,
    ) {
    }

    #[Route('/api/health/daily-energy', name: 'api_health_daily_energy', methods: ['GET'])]
    public function read(Request $request): JsonResponse
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
            return new JsonResponse($this->reader->read($user, $from, $to));
        } catch (\InvalidArgumentException $e) {
            return new JsonResponse(['error' => $e->getMessage()], Response::HTTP_BAD_REQUEST);
        }
    }
}
