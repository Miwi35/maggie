<?php

declare(strict_types=1);

namespace Maggie\Finance\Controller;

use Maggie\Core\Entity\User;
use Maggie\Finance\Exception\ScenarioNotFound;
use Maggie\Finance\UseCase\GetFinanceTimeline;
use Symfony\Bundle\SecurityBundle\Security;
use Symfony\Component\HttpFoundation\JsonResponse;
use Symfony\Component\HttpFoundation\Request;
use Symfony\Component\HttpFoundation\Response;
use Symfony\Component\Routing\Attribute\Route;

/**
 * The 20-year frise (MAG-554): the events of the current scenario, and of a
 * second one with `?compare=<scenario id>` (a stored id, or `auto-rapid`,
 * `auto-balanced`, `auto-quality`). Read-only; facts, never a verdict.
 */
final class FinanceTimelineController
{
    public function __construct(
        private readonly Security $security,
        private readonly GetFinanceTimeline $getFinanceTimeline,
    ) {
    }

    #[Route('/api/finance/timeline', name: 'api_finance_timeline', methods: ['GET'])]
    public function get(Request $request): JsonResponse
    {
        $user = $this->security->getUser();
        if (!$user instanceof User) {
            return new JsonResponse(['error' => 'Unauthorized'], Response::HTTP_UNAUTHORIZED);
        }

        $compare = $request->query->get('compare');
        if ('' === $compare) {
            return new JsonResponse(['error' => 'compare is a scenario id.'], Response::HTTP_BAD_REQUEST);
        }

        try {
            return new JsonResponse($this->getFinanceTimeline->execute($user, $compare));
        } catch (ScenarioNotFound $e) {
            return new JsonResponse(['error' => $e->getMessage()], Response::HTTP_NOT_FOUND);
        } catch (\DomainException $e) {
            return new JsonResponse(['error' => $e->getMessage()], Response::HTTP_BAD_REQUEST);
        }
    }
}
