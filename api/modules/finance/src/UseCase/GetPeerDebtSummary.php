<?php

declare(strict_types=1);

namespace Maggie\Finance\UseCase;

use Maggie\Core\Entity\User;
use Maggie\Finance\Enum\PeerDebtDirection;
use Maggie\Finance\Repository\PeerDebtRepository;

/** « On te doit / Tu dois »: what is left to give back each way, all contacts together. */
class GetPeerDebtSummary
{
    public function __construct(
        private readonly PeerDebtRepository $peerDebtRepository,
    ) {
    }

    /** @return array{owedToMeCents: int, iOweCents: int} */
    public function execute(User $user): array
    {
        return [
            'owedToMeCents' => $this->peerDebtRepository->sumRemaining($user, PeerDebtDirection::OwedToMe),
            'iOweCents' => $this->peerDebtRepository->sumRemaining($user, PeerDebtDirection::IOwe),
        ];
    }
}
