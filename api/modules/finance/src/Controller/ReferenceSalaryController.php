<?php

declare(strict_types=1);

namespace Maggie\Finance\Controller;

use Maggie\Core\Entity\User;
use Maggie\Finance\UseCase\GetReferenceSalary;
use Symfony\Bundle\SecurityBundle\Security;
use Symfony\Component\HttpFoundation\JsonResponse;
use Symfony\Component\HttpFoundation\Response;
use Symfony\Component\Routing\Attribute\Route;

/** The salary the month is judged on, and where that figure comes from. */
final class ReferenceSalaryController
{
    public function __construct(
        private readonly Security $security,
        private readonly GetReferenceSalary $getReferenceSalary,
    ) {
    }

    #[Route('/api/finance/reference-salary', name: 'api_finance_reference_salary', methods: ['GET'])]
    public function __invoke(): JsonResponse
    {
        $user = $this->security->getUser();
        if (!$user instanceof User) {
            return new JsonResponse(['error' => 'Unauthorized'], Response::HTTP_UNAUTHORIZED);
        }

        return new JsonResponse($this->getReferenceSalary->execute($user));
    }
}
