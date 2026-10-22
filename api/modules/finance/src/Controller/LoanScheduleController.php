<?php

declare(strict_types=1);

namespace Maggie\Finance\Controller;

use Maggie\Core\Entity\User;
use Maggie\Finance\Repository\LoanRepository;
use Maggie\Finance\Service\LoanSchedule;
use Symfony\Bundle\SecurityBundle\Security;
use Symfony\Component\HttpFoundation\JsonResponse;
use Symfony\Component\HttpFoundation\Response;
use Symfony\Component\Routing\Attribute\Route;
use Symfony\Component\Uid\Ulid;

/**
 * The capital still owed month by month until a loan ends (MAG-523): the curve
 * of a loan's sheet. `freedOn` is the last payment, null when the loan runs
 * past the longest schedule.
 */
final class LoanScheduleController
{
    public function __construct(
        private readonly Security $security,
        private readonly LoanRepository $loanRepository,
        private readonly LoanSchedule $loanSchedule,
    ) {
    }

    #[Route('/api/finance/loans/{id}/schedule', name: 'api_finance_loan_schedule', methods: ['GET'])]
    public function __invoke(string $id): JsonResponse
    {
        $user = $this->security->getUser();
        if (!$user instanceof User) {
            return new JsonResponse(['error' => 'Unauthorized'], Response::HTTP_UNAUTHORIZED);
        }

        $loan = Ulid::isValid($id) ? $this->loanRepository->findOneBy(['id' => $id, 'user' => $user]) : null;
        if (null === $loan) {
            return new JsonResponse(['error' => 'Loan not found.'], Response::HTTP_NOT_FOUND);
        }

        $rows = $this->loanSchedule->rows($loan, new \DateTimeImmutable('first day of this month'));
        $last = [] === $rows ? null : $rows[array_key_last($rows)];
        $freedOn = null !== $last && 0 === $last['remainingCents'] ? $last['month'] : null;

        return new JsonResponse([
            'principalRemainingCents' => $loan->getPrincipalRemainingCents(),
            'freedOn' => $freedOn,
            'monthsRemaining' => null === $freedOn ? null : \count($rows),
            'totalInterestCents' => array_sum(array_column($rows, 'interestCents')),
            'months' => array_map(
                static fn (array $row): array => ['month' => $row['month'], 'remainingCents' => $row['remainingCents']],
                $rows,
            ),
        ]);
    }
}
