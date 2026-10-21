<?php

declare(strict_types=1);

namespace Maggie\Finance\Controller;

use Maggie\Core\Entity\User;
use Maggie\Finance\Repository\LoanRepository;
use Maggie\Finance\UseCase\SimulateEarlyRepayment;
use Symfony\Bundle\SecurityBundle\Security;
use Symfony\Component\HttpFoundation\JsonResponse;
use Symfony\Component\HttpFoundation\Request;
use Symfony\Component\HttpFoundation\Response;
use Symfony\Component\Routing\Attribute\Route;
use Symfony\Component\Uid\Ulid;

/**
 * What repaying a loan early would cost (MAG-522): `?amountCents=` for a
 * partial repayment, nothing for the whole capital. Answers
 * `{amountCents, penaltyMode, penaltyCents, remainingInterestCents, realGainCents}`.
 */
final class EarlyRepaymentController
{
    public function __construct(
        private readonly Security $security,
        private readonly LoanRepository $loanRepository,
        private readonly SimulateEarlyRepayment $simulateEarlyRepayment,
    ) {
    }

    #[Route('/api/finance/loans/{id}/early-repayment', name: 'api_finance_loan_early_repayment', methods: ['GET'])]
    public function __invoke(string $id, Request $request): JsonResponse
    {
        $user = $this->security->getUser();
        if (!$user instanceof User) {
            return new JsonResponse(['error' => 'Unauthorized'], Response::HTTP_UNAUTHORIZED);
        }

        $loan = Ulid::isValid($id) ? $this->loanRepository->findOneBy(['id' => $id, 'user' => $user]) : null;
        if (null === $loan) {
            return new JsonResponse(['error' => 'Loan not found.'], Response::HTTP_NOT_FOUND);
        }

        $amount = $request->query->get('amountCents');
        if (null !== $amount && 1 !== preg_match('/^\d+$/', (string) $amount)) {
            return new JsonResponse(['error' => 'amountCents must be a positive integer of cents.'], Response::HTTP_BAD_REQUEST);
        }

        try {
            return new JsonResponse($this->simulateEarlyRepayment->execute($loan, null === $amount ? null : (int) $amount));
        } catch (\DomainException $e) {
            return new JsonResponse(['error' => $e->getMessage()], Response::HTTP_BAD_REQUEST);
        }
    }
}
