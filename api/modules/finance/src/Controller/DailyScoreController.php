<?php

declare(strict_types=1);

namespace Maggie\Finance\Controller;

use Maggie\Core\Entity\User;
use Maggie\Finance\UseCase\GetDailyScore;
use Symfony\Bundle\SecurityBundle\Security;
use Symfony\Component\HttpFoundation\JsonResponse;
use Symfony\Component\HttpFoundation\Request;
use Symfony\Component\HttpFoundation\Response;
use Symfony\Component\Routing\Attribute\Route;

final class DailyScoreController
{
    public function __construct(
        private readonly Security $security,
        private readonly GetDailyScore $getDailyScore,
    ) {
    }

    #[Route('/api/finance/daily-score', name: 'api_finance_daily_score', methods: ['GET'])]
    public function __invoke(Request $request): JsonResponse
    {
        $user = $this->security->getUser();
        if (!$user instanceof User) {
            return new JsonResponse(['error' => 'Unauthorized'], Response::HTTP_UNAUTHORIZED);
        }

        $now = new \DateTimeImmutable();
        $year = $request->query->get('year');
        $month = $request->query->get('month');

        $year = null === $year ? (int) $now->format('Y') : (int) $year;
        $month = null === $month ? (int) $now->format('n') : (int) $month;

        if ($month < 1 || $month > 12) {
            return new JsonResponse(['error' => 'month must be between 1 and 12'], Response::HTTP_BAD_REQUEST);
        }

        if ($year < 2000 || $year > 2100) {
            return new JsonResponse(['error' => 'year must be between 2000 and 2100'], Response::HTTP_BAD_REQUEST);
        }

        return new JsonResponse($this->getDailyScore->execute($user, $year, $month));
    }
}
