<?php

declare(strict_types=1);

namespace Maggie\Finance\Controller;

use Maggie\Core\Entity\User;
use Maggie\Finance\UseCase\GetTreasuryOutlook;
use Symfony\Bundle\SecurityBundle\Security;
use Symfony\Component\HttpFoundation\JsonResponse;
use Symfony\Component\HttpFoundation\Response;
use Symfony\Component\Routing\Attribute\Route;

/** The treasury until the salary comes: delay, projected balance, overdraft ahead and the advance to make. A pure read. */
final class TreasuryOutlookController
{
    public function __construct(
        private readonly Security $security,
        private readonly GetTreasuryOutlook $getTreasuryOutlook,
    ) {
    }

    #[Route('/api/finance/treasury-outlook', name: 'api_finance_treasury_outlook', methods: ['GET'])]
    public function __invoke(): JsonResponse
    {
        $user = $this->security->getUser();
        if (!$user instanceof User) {
            return new JsonResponse(['error' => 'Unauthorized'], Response::HTTP_UNAUTHORIZED);
        }

        return new JsonResponse($this->getTreasuryOutlook->execute($user));
    }
}
