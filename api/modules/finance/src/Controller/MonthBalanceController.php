<?php

declare(strict_types=1);

namespace Maggie\Finance\Controller;

use Maggie\Core\Entity\User;
use Maggie\Finance\Service\FinanceReviewAppointment;
use Maggie\Finance\UseCase\GetMonthBalance;
use Symfony\Bundle\SecurityBundle\Security;
use Symfony\Component\HttpFoundation\JsonResponse;
use Symfony\Component\HttpFoundation\Request;
use Symfony\Component\HttpFoundation\Response;
use Symfony\Component\Routing\Attribute\Route;

/** The balance of a month, drawn by the « État courant » dashboard (MAG-457). */
final class MonthBalanceController
{
    public function __construct(
        private readonly Security $security,
        private readonly GetMonthBalance $getMonthBalance,
        private readonly FinanceReviewAppointment $reviewAppointment,
    ) {
    }

    #[Route('/api/finance/month-balance', name: 'api_finance_month_balance', methods: ['GET'])]
    public function __invoke(Request $request): JsonResponse
    {
        $user = $this->security->getUser();
        if (!$user instanceof User) {
            return new JsonResponse(['error' => 'Unauthorized'], Response::HTTP_UNAUTHORIZED);
        }

        $month = $request->query->get('month', (new \DateTimeImmutable('today'))->format('Y-m'));
        if (1 !== preg_match('/^(20\d{2}|2100)-(0[1-9]|1[0-2])$/', $month)) {
            return new JsonResponse(['error' => 'month must be a month as YYYY-MM, between 2000 and 2100'], Response::HTTP_BAD_REQUEST);
        }

        $balance = $this->getMonthBalance->execute($user, new \DateTimeImmutable($month.'-01'));
        // Opening the finances lays down the monthly review's appointment, once (MAG-456).
        $this->reviewAppointment->ensure($user);

        return new JsonResponse($balance);
    }
}
