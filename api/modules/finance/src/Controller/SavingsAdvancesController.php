<?php

declare(strict_types=1);

namespace Maggie\Finance\Controller;

use Maggie\Core\Entity\User;
use Maggie\Finance\UseCase\GetSalaryDelay;
use Maggie\Finance\UseCase\GetSavingsAdvances;
use Symfony\Bundle\SecurityBundle\Security;
use Symfony\Component\HttpFoundation\JsonResponse;
use Symfony\Component\HttpFoundation\Response;
use Symfony\Component\Routing\Attribute\Route;

/** What was taken from each savings source while the salary was late, and the delay itself. */
final class SavingsAdvancesController
{
    public function __construct(
        private readonly Security $security,
        private readonly GetSavingsAdvances $getSavingsAdvances,
        private readonly GetSalaryDelay $getSalaryDelay,
    ) {
    }

    #[Route('/api/finance/savings-advances', name: 'api_finance_savings_advances', methods: ['GET'])]
    public function __invoke(): JsonResponse
    {
        $user = $this->security->getUser();
        if (!$user instanceof User) {
            return new JsonResponse(['error' => 'Unauthorized'], Response::HTTP_UNAUTHORIZED);
        }

        return new JsonResponse(
            $this->getSavingsAdvances->execute($user) + ['salaryDelay' => $this->getSalaryDelay->execute($user)],
        );
    }
}
