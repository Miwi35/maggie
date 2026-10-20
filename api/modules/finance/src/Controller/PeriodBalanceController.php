<?php

declare(strict_types=1);

namespace Maggie\Finance\Controller;

use Maggie\Core\Entity\User;
use Maggie\Finance\UseCase\GetPeriodBalance;
use Maggie\Finance\UseCase\PlanningPeriod;
use Symfony\Bundle\SecurityBundle\Security;
use Symfony\Component\HttpFoundation\JsonResponse;
use Symfony\Component\HttpFoundation\Request;
use Symfony\Component\HttpFoundation\Response;
use Symfony\Component\Routing\Attribute\Route;

/**
 * The bilan the augmented reviews show (MAG-462): the quarter, semester or
 * year that the review of `month` closes. A month that closes nothing has no
 * bilan of a period: 400.
 */
final class PeriodBalanceController
{
    private const MONTH = '/^\d{4}-(0[1-9]|1[0-2])$/';

    public function __construct(
        private readonly Security $security,
        private readonly GetPeriodBalance $getPeriodBalance,
    ) {
    }

    #[Route('/api/finance/review/period-balance', name: 'api_finance_review_period_balance', methods: ['GET'])]
    public function __invoke(Request $request): JsonResponse
    {
        $user = $this->security->getUser();
        if (!$user instanceof User) {
            return new JsonResponse(['error' => 'Unauthorized'], Response::HTTP_UNAUTHORIZED);
        }

        $month = $request->query->get('month');
        if (!\is_string($month) || 1 !== preg_match(self::MONTH, $month)) {
            return new JsonResponse(['error' => 'month must be a month as YYYY-MM'], Response::HTTP_BAD_REQUEST);
        }

        try {
            $period = PlanningPeriod::closingAt(new \DateTimeImmutable($month.'-01'));
        } catch (\InvalidArgumentException $e) {
            return new JsonResponse(['error' => $e->getMessage()], Response::HTTP_BAD_REQUEST);
        }
        if (null === $period) {
            return new JsonResponse(['error' => 'month closes no quarter, semester or year'], Response::HTTP_BAD_REQUEST);
        }

        return new JsonResponse($this->getPeriodBalance->execute($user, $period));
    }
}
