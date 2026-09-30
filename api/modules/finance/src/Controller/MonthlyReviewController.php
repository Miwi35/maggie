<?php

declare(strict_types=1);

namespace Maggie\Finance\Controller;

use Maggie\Core\Entity\User;
use Maggie\Finance\UseCase\GetMonthlyReview;
use Symfony\Bundle\SecurityBundle\Security;
use Symfony\Component\HttpFoundation\JsonResponse;
use Symfony\Component\HttpFoundation\Request;
use Symfony\Component\HttpFoundation\Response;
use Symfony\Component\Routing\Attribute\Route;

final class MonthlyReviewController
{
    public function __construct(
        private readonly Security $security,
        private readonly GetMonthlyReview $getMonthlyReview,
    ) {
    }

    #[Route('/api/finance/monthly-review', name: 'api_finance_monthly_review', methods: ['GET'])]
    public function __invoke(Request $request): JsonResponse
    {
        $user = $this->security->getUser();
        if (!$user instanceof User) {
            return new JsonResponse(['error' => 'Unauthorized'], Response::HTTP_UNAUTHORIZED);
        }

        // A review looks at the month just ended.
        $period = new \DateTimeImmutable('first day of last month');
        $year = $request->query->get('year');
        $month = $request->query->get('month');

        $year = null === $year ? (int) $period->format('Y') : (int) $year;
        $month = null === $month ? (int) $period->format('n') : (int) $month;

        if ($month < 1 || $month > 12) {
            return new JsonResponse(['error' => 'month must be between 1 and 12'], Response::HTTP_BAD_REQUEST);
        }

        if ($year < 2000 || $year > 2100) {
            return new JsonResponse(['error' => 'year must be between 2000 and 2100'], Response::HTTP_BAD_REQUEST);
        }

        return new JsonResponse($this->getMonthlyReview->execute($user, $year, $month));
    }
}
