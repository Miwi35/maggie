<?php

declare(strict_types=1);

namespace Maggie\Finance\Controller;

use Maggie\Core\Entity\User;
use Maggie\Finance\Repository\FinanceDashboardSnapshotRepository;
use Maggie\Finance\UseCase\GetFinanceDashboard;
use Maggie\Finance\UseCase\GetFinanceScores;
use Symfony\Bundle\SecurityBundle\Security;
use Symfony\Component\HttpFoundation\JsonResponse;
use Symfony\Component\HttpFoundation\Request;
use Symfony\Component\HttpFoundation\Response;
use Symfony\Component\Routing\Attribute\Route;

/**
 * The Finance Dashboard is read from its read model (MAG-545): one statement,
 * the body as it was stored. When the model does not hold the month asked for,
 * or a write has outdated it and its rebuild is not stored yet, the dashboard
 * is computed live — the same computation, so the same answer. A read writes
 * nothing either way.
 */
final class FinanceDashboardController
{
    public function __construct(
        private readonly Security $security,
        private readonly GetFinanceDashboard $getFinanceDashboard,
        private readonly FinanceDashboardSnapshotRepository $snapshots,
        private readonly GetFinanceScores $getFinanceScores,
    ) {
    }

    #[Route('/api/finance/dashboard', name: 'api_finance_dashboard', methods: ['GET'])]
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

        $stored = $this->snapshots->fetchFreshPayload($user, $year, $month, $this->getFinanceScores->resolveDate($user, null));
        if (null !== $stored) {
            return new JsonResponse($stored, Response::HTTP_OK, [], true);
        }

        return new JsonResponse($this->getFinanceDashboard->execute($user, $year, $month));
    }
}
