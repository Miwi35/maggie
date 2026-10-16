<?php

declare(strict_types=1);

namespace Maggie\Finance\Controller;

use Maggie\Core\Entity\User;
use Maggie\Finance\UseCase\GetPeerDebtSummary;
use Symfony\Bundle\SecurityBundle\Security;
use Symfony\Component\HttpFoundation\JsonResponse;
use Symfony\Component\HttpFoundation\Response;
use Symfony\Component\Routing\Attribute\Route;

/** What the owner is owed and owes, all contacts together (MAG-451; by contact with MAG-455). */
final class PeerDebtSummaryController
{
    public function __construct(
        private readonly Security $security,
        private readonly GetPeerDebtSummary $summary,
    ) {
    }

    #[Route('/api/finance/peer-debts/summary', name: 'api_finance_peer_debts_summary', methods: ['GET'])]
    public function __invoke(): JsonResponse
    {
        $user = $this->security->getUser();
        if (!$user instanceof User) {
            return new JsonResponse(['error' => 'Unauthorized'], Response::HTTP_UNAUTHORIZED);
        }

        return new JsonResponse($this->summary->execute($user));
    }
}
