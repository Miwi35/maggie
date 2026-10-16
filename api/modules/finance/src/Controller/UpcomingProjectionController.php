<?php

declare(strict_types=1);

namespace Maggie\Finance\Controller;

use Maggie\Core\Entity\User;
use Maggie\Finance\UseCase\GetUpcomingProjection;
use Symfony\Bundle\SecurityBundle\Security;
use Symfony\Component\HttpFoundation\JsonResponse;
use Symfony\Component\HttpFoundation\Request;
use Symfony\Component\HttpFoundation\Response;
use Symfony\Component\Routing\Attribute\Route;

/**
 * Dashboard › À venir: the week and the month after the day read (today unless
 * `date` says otherwise, as YYYY-MM-DD), projected with the envelopes as
 * adjusted. A pure read.
 */
final class UpcomingProjectionController
{
    public function __construct(
        private readonly Security $security,
        private readonly GetUpcomingProjection $getUpcomingProjection,
    ) {
    }

    #[Route('/api/finance/upcoming', name: 'api_finance_upcoming', methods: ['GET'])]
    public function __invoke(Request $request): JsonResponse
    {
        $user = $this->security->getUser();
        if (!$user instanceof User) {
            return new JsonResponse(['error' => 'Unauthorized'], Response::HTTP_UNAUTHORIZED);
        }

        $date = $request->query->get('date');
        $today = null;
        if (null !== $date) {
            $today = 1 === preg_match('/^\d{4}-\d{2}-\d{2}$/', $date)
                ? \DateTimeImmutable::createFromFormat('!Y-m-d', $date)
                : false;
            if (false === $today || $today->format('Y-m-d') !== $date) {
                return new JsonResponse(['error' => 'date must be a day as YYYY-MM-DD'], Response::HTTP_BAD_REQUEST);
            }
        }

        return new JsonResponse($this->getUpcomingProjection->execute($user, $today));
    }
}
