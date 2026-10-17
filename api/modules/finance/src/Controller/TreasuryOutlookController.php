<?php

declare(strict_types=1);

namespace Maggie\Finance\Controller;

use Maggie\Core\Entity\User;
use Maggie\Finance\UseCase\GetTreasuryOutlook;
use Symfony\Bundle\SecurityBundle\Security;
use Symfony\Component\HttpFoundation\JsonResponse;
use Symfony\Component\HttpFoundation\Request;
use Symfony\Component\HttpFoundation\Response;
use Symfony\Component\Routing\Attribute\Route;

/** The treasury over a horizon (`months`, 1 to 12; the end of the next month by default): delay, projected balance, overdraft ahead and the advance to make. A pure read. */
final class TreasuryOutlookController
{
    public function __construct(
        private readonly Security $security,
        private readonly GetTreasuryOutlook $getTreasuryOutlook,
    ) {
    }

    #[Route('/api/finance/treasury-outlook', name: 'api_finance_treasury_outlook', methods: ['GET'])]
    public function __invoke(Request $request): JsonResponse
    {
        $user = $this->security->getUser();
        if (!$user instanceof User) {
            return new JsonResponse(['error' => 'Unauthorized'], Response::HTTP_UNAUTHORIZED);
        }

        $months = null;
        if ($request->query->has('months')) {
            $months = filter_var($request->query->get('months'), FILTER_VALIDATE_INT);
            if (false === $months || $months < 1 || $months > GetTreasuryOutlook::MAX_MONTHS) {
                return new JsonResponse(['error' => sprintf('months must be an integer between 1 and %d.', GetTreasuryOutlook::MAX_MONTHS)], Response::HTTP_BAD_REQUEST);
            }
        }

        return new JsonResponse($this->getTreasuryOutlook->execute($user, null, $months));
    }
}
