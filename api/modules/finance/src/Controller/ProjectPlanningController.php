<?php

declare(strict_types=1);

namespace Maggie\Finance\Controller;

use Maggie\Core\Entity\User;
use Maggie\Finance\UseCase\GetProjectPlanning;
use Symfony\Bundle\SecurityBundle\Security;
use Symfony\Component\HttpFoundation\JsonResponse;
use Symfony\Component\HttpFoundation\Response;
use Symfony\Component\Routing\Attribute\Route;

/**
 * The projects over the next 36 months (MAG-526). Not under
 * `/finance/projects/…`: API Platform's `Get /finance/projects/{id}` would
 * catch it.
 */
final class ProjectPlanningController
{
    public function __construct(
        private readonly Security $security,
        private readonly GetProjectPlanning $getProjectPlanning,
    ) {
    }

    #[Route('/api/finance/project-planning', name: 'api_finance_project_planning', methods: ['GET'])]
    public function __invoke(): JsonResponse
    {
        $user = $this->security->getUser();
        if (!$user instanceof User) {
            return new JsonResponse(['error' => 'Unauthorized'], Response::HTTP_UNAUTHORIZED);
        }

        return new JsonResponse($this->getProjectPlanning->execute($user));
    }
}
