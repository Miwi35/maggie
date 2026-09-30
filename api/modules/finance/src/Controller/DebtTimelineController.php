<?php

declare(strict_types=1);

namespace Maggie\Finance\Controller;

use Maggie\Core\Entity\User;
use Maggie\Finance\UseCase\GetDebtTimeline;
use Symfony\Bundle\SecurityBundle\Security;
use Symfony\Component\HttpFoundation\JsonResponse;
use Symfony\Component\HttpFoundation\Request;
use Symfony\Component\HttpFoundation\Response;
use Symfony\Component\Routing\Attribute\Route;

final class DebtTimelineController
{
    public function __construct(
        private readonly Security $security,
        private readonly GetDebtTimeline $getDebtTimeline,
    ) {
    }

    #[Route('/api/finance/debt-timeline', name: 'api_finance_debt_timeline', methods: ['GET'])]
    public function __invoke(Request $request): JsonResponse
    {
        $user = $this->security->getUser();
        if (!$user instanceof User) {
            return new JsonResponse(['error' => 'Unauthorized'], Response::HTTP_UNAUTHORIZED);
        }

        $horizon = $request->query->get('months');
        $horizon = null === $horizon ? null : (int) $horizon;

        if (null !== $horizon && ($horizon < 1 || $horizon > 480)) {
            return new JsonResponse(
                ['error' => 'months must be between 1 and 480'],
                Response::HTTP_BAD_REQUEST,
            );
        }

        return new JsonResponse($this->getDebtTimeline->execute($user, $horizon));
    }
}
