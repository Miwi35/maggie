<?php

declare(strict_types=1);

namespace Maggie\Finance\Controller;

use Maggie\Core\Entity\User;
use Maggie\Finance\UseCase\GetRecurringOperations;
use Symfony\Bundle\SecurityBundle\Security;
use Symfony\Component\HttpFoundation\JsonResponse;
use Symfony\Component\HttpFoundation\Response;
use Symfony\Component\Routing\Attribute\Route;

/** What the recurring operations cost, when they fall due, what is missing and what is waiting. */
final class RecurringOperationsOverviewController
{
    public function __construct(
        private readonly Security $security,
        private readonly GetRecurringOperations $getRecurringOperations,
    ) {
    }

    #[Route('/api/finance/recurring-operations/overview', name: 'api_finance_recurring_operations_overview', methods: ['GET'])]
    public function __invoke(): JsonResponse
    {
        $user = $this->security->getUser();
        if (!$user instanceof User) {
            return new JsonResponse(['error' => 'Unauthorized'], Response::HTTP_UNAUTHORIZED);
        }

        return new JsonResponse($this->getRecurringOperations->execute($user));
    }
}
