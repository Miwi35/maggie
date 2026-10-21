<?php

declare(strict_types=1);

namespace Maggie\Finance\Controller;

use Maggie\Core\Entity\User;
use Maggie\Finance\Repository\ProjectRepository;
use Maggie\Finance\UseCase\GetProjectOptions;
use Symfony\Bundle\SecurityBundle\Security;
use Symfony\Component\HttpFoundation\JsonResponse;
use Symfony\Component\HttpFoundation\Request;
use Symfony\Component\HttpFoundation\Response;
use Symfony\Component\Routing\Attribute\Route;

/**
 * A project's three base options, cash, loan and partial, priced (MAG-524):
 * `?loanRateBasisPoints=&loanMonths=&downPaymentCents=&yieldBasisPoints=`.
 * Each answers `{totalCostCents, netCostCents, monthlyPaymentCents,
 * durationMonths, availableInMonths, capitalKeptCents, nImpactMonths}` and
 * what they are made of. A missing parameter is a 400 that lists them all.
 */
final class ProjectOptionsController
{
    private const PARAMETERS = ['loanRateBasisPoints', 'loanMonths', 'downPaymentCents', 'yieldBasisPoints'];

    public function __construct(
        private readonly Security $security,
        private readonly ProjectRepository $projectRepository,
        private readonly GetProjectOptions $getProjectOptions,
    ) {
    }

    #[Route('/api/finance/projects/{id}/options', name: 'api_finance_project_options', methods: ['GET'])]
    public function __invoke(string $id, Request $request): JsonResponse
    {
        $user = $this->security->getUser();
        if (!$user instanceof User) {
            return new JsonResponse(['error' => 'Unauthorized'], Response::HTTP_UNAUTHORIZED);
        }

        $project = $this->projectRepository->findOneOfUser($user, $id);
        if (null === $project) {
            return new JsonResponse(['error' => 'Project not found.'], Response::HTTP_NOT_FOUND);
        }

        $values = [];
        foreach (self::PARAMETERS as $parameter) {
            $value = $request->query->get($parameter);
            if (null === $value || '' === $value) {
                $values[$parameter] = null;
                continue;
            }
            if (1 !== preg_match('/^\d+$/', (string) $value)) {
                return new JsonResponse(['error' => "{$parameter} must be a non-negative integer."], Response::HTTP_BAD_REQUEST);
            }
            $values[$parameter] = (int) $value;
        }

        try {
            return new JsonResponse($this->getProjectOptions->execute(
                $project,
                $user,
                $values['loanRateBasisPoints'],
                $values['loanMonths'],
                $values['downPaymentCents'],
                $values['yieldBasisPoints'],
            ));
        } catch (\DomainException $e) {
            return new JsonResponse(['error' => $e->getMessage()], Response::HTTP_BAD_REQUEST);
        }
    }
}
