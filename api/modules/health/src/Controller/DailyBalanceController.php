<?php

declare(strict_types=1);

namespace Maggie\Health\Controller;

use Maggie\Core\Entity\User;
use Maggie\Health\Service\DailyBalanceReader;
use Symfony\Bundle\SecurityBundle\Security;
use Symfony\Component\HttpFoundation\JsonResponse;
use Symfony\Component\HttpFoundation\Request;
use Symfony\Component\HttpFoundation\Response;
use Symfony\Component\Routing\Attribute\Route;

/**
 * The day's calorie balance (MAG-748): `GET /api/health/daily-balance?date=`
 * for one day, with the planned meals not eaten yet (`plannedMeals`, the
 * journal's « Mangé » rows), or `?from=&to=` for `{days: [...]}`, at most 31,
 * without them — the seven days' bars of Santé › Nutrition.
 */
final class DailyBalanceController
{
    public function __construct(
        private readonly Security $security,
        private readonly DailyBalanceReader $reader,
    ) {
    }

    #[Route('/api/health/daily-balance', name: 'api_health_daily_balance', methods: ['GET'])]
    public function read(Request $request): JsonResponse
    {
        $user = $this->security->getUser();
        if (!$user instanceof User) {
            return new JsonResponse(['error' => 'Unauthorized'], Response::HTTP_UNAUTHORIZED);
        }

        $date = $request->query->get('date');
        $from = $request->query->get('from');
        $to = $request->query->get('to');

        try {
            if (\is_string($date)) {
                return new JsonResponse($this->reader->day($user, $date));
            }
            if (\is_string($from) && \is_string($to)) {
                return new JsonResponse(['days' => $this->reader->range($user, $from, $to)]);
            }
        } catch (\InvalidArgumentException $e) {
            return new JsonResponse(['error' => $e->getMessage()], Response::HTTP_BAD_REQUEST);
        }

        return new JsonResponse(['error' => 'date is required, as YYYY-MM-DD (or from and to for several days).'], Response::HTTP_BAD_REQUEST);
    }
}
