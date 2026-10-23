<?php

declare(strict_types=1);

namespace Maggie\Finance\Controller;

use Maggie\Core\Entity\User;
use Maggie\Finance\Exception\ScenarioNotFound;
use Maggie\Finance\UseCase\GetPlacementSimulation;
use Symfony\Bundle\SecurityBundle\Security;
use Symfony\Component\HttpFoundation\JsonResponse;
use Symfony\Component\HttpFoundation\Request;
use Symfony\Component\HttpFoundation\Response;
use Symfony\Component\Routing\Attribute\Route;

/**
 * A placement month by month (MAG-555): answers
 * `{final, paid, interest, rent, curve: [{month, capital, paid}]}`, integer
 * cents, month 0 to years × 12, and with the capital the projects free from it
 * (MAG-556) `withdrawals`, `curveWithout`, `curveWith`, `gapAtEnd`, `finalWith`,
 * `rentWith`, `withdrawn`. `?scenarioId=` picks the scenario whose projects
 * count, else the current one, else all the projects. The phone, the tablet and
 * the admin draw the curves from it.
 */
final class PlacementSimulationController
{
    public function __construct(
        private readonly Security $security,
        private readonly GetPlacementSimulation $getPlacementSimulation,
    ) {
    }

    #[Route('/api/finance/placements/{id}/simulation', name: 'api_finance_placement_simulation', methods: ['GET'])]
    public function __invoke(string $id, Request $request): JsonResponse
    {
        $user = $this->security->getUser();
        if (!$user instanceof User) {
            return new JsonResponse(['error' => 'Unauthorized'], Response::HTTP_UNAUTHORIZED);
        }

        $scenarioId = $request->query->get('scenarioId');
        try {
            $simulation = $this->getPlacementSimulation->execute($user, $id, \is_string($scenarioId) && '' !== $scenarioId ? $scenarioId : null);
        } catch (ScenarioNotFound) {
            return new JsonResponse(['error' => 'Scenario not found.'], Response::HTTP_NOT_FOUND);
        }
        if (null === $simulation) {
            return new JsonResponse(['error' => 'Placement not found.'], Response::HTTP_NOT_FOUND);
        }

        return new JsonResponse($simulation);
    }
}
