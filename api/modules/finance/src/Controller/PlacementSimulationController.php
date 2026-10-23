<?php

declare(strict_types=1);

namespace Maggie\Finance\Controller;

use Maggie\Core\Entity\User;
use Maggie\Finance\Repository\PlacementRepository;
use Maggie\Finance\Service\PlacementSimulator;
use Symfony\Bundle\SecurityBundle\Security;
use Symfony\Component\HttpFoundation\JsonResponse;
use Symfony\Component\HttpFoundation\Response;
use Symfony\Component\Routing\Attribute\Route;

/**
 * A placement month by month (MAG-555): answers
 * `{final, paid, interest, rent, curve: [{month, capital, paid}]}`, integer
 * cents, month 0 to years × 12. The phone, the tablet and the admin draw the
 * curve from it.
 */
final class PlacementSimulationController
{
    public function __construct(
        private readonly Security $security,
        private readonly PlacementRepository $placementRepository,
        private readonly PlacementSimulator $simulator,
    ) {
    }

    #[Route('/api/finance/placements/{id}/simulation', name: 'api_finance_placement_simulation', methods: ['GET'])]
    public function __invoke(string $id): JsonResponse
    {
        $user = $this->security->getUser();
        if (!$user instanceof User) {
            return new JsonResponse(['error' => 'Unauthorized'], Response::HTTP_UNAUTHORIZED);
        }

        $placement = $this->placementRepository->findOneOfUser($user, $id);
        if (null === $placement) {
            return new JsonResponse(['error' => 'Placement not found.'], Response::HTTP_NOT_FOUND);
        }

        return new JsonResponse($this->simulator->of($placement));
    }
}
