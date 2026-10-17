<?php

declare(strict_types=1);

namespace Maggie\Finance\UseCase;

use Doctrine\ORM\EntityManagerInterface;
use Maggie\Core\Entity\User;
use Maggie\Finance\Entity\Account;
use Maggie\Finance\Entity\PeerDebt;
use Maggie\Finance\Entity\SafetyCushion;
use Maggie\Finance\Enum\CushionState;
use Maggie\Finance\Enum\PeerDebtDirection;
use Maggie\Finance\Repository\AccountRepository;
use Maggie\Finance\Repository\PeerDebtRepository;
use Maggie\Finance\Repository\SafetyCushionRepository;

/**
 * Where the safety net stands, and what it would take to fill it back up.
 *
 * The current amount is never stored: it is the balance of the accounts
 * flagged as cushion, less what is lent to people and not yet given back
 * (MAG-454), so the net follows the accounts without double entry and fills
 * back up with every repayment. A treasury advance is not lent to a person: it
 * already lowered the account's balance, and a dette never raises the net.
 * The target follows the reference salary — declared on the cushion, or
 * measured from the recurring « Salaire » — not the raw field alone.
 */
class GetCushionStatus
{
    public function __construct(
        private readonly SafetyCushionRepository $cushionRepository,
        private readonly AccountRepository $accountRepository,
        private readonly EntityManagerInterface $em,
        private readonly GetReferenceSalary $getReferenceSalary,
        private readonly PeerDebtRepository $peerDebtRepository,
    ) {
    }

    /** @return array<string, mixed> */
    public function execute(User $user): array
    {
        $cushion = $this->cushionRepository->findOneByUser($user) ?? $this->createFor($user);

        $accounts = array_values(array_filter(
            $this->accountRepository->findByUser($user),
            static fn (Account $account) => $account->isCushion(),
        ));

        $claims = array_values(array_filter(
            $this->peerDebtRepository->findByUser($user),
            static fn (PeerDebt $debt) => PeerDebtDirection::OwedToMe === $debt->getDirection() && $debt->getRemainingCents() > 0,
        ));
        $peerClaimsCents = array_sum(array_map(static fn (PeerDebt $debt) => $debt->getRemainingCents(), $claims));

        $currentCents = array_sum(array_map(
            static fn (Account $account) => $account->getBalanceCents(),
            $accounts,
        )) - $peerClaimsCents;

        $reference = $this->getReferenceSalary->execute($user);
        $monthlyIncomeCents = $reference['monthlyCents'] ?? 0;

        $targetCents = $cushion->getTargetMonths() * $monthlyIncomeCents;
        $deficitCents = max(0, $targetCents - $currentCents);
        $isComplete = $targetCents > 0 && $currentCents >= $targetCents;

        // Remember the first time the target was ever reached: that is what
        // tells "still building" from "was complete, now recharging".
        if ($isComplete && null === $cushion->getCompletedAt()) {
            $cushion->setCompletedAt(new \DateTimeImmutable());
            $this->em->flush();
        }

        $state = match (true) {
            $isComplete => CushionState::Complete,
            null !== $cushion->getCompletedAt() => CushionState::Recharging,
            default => CushionState::Building,
        };

        $plan = $this->rechargePlan($cushion, $deficitCents);

        return [
            'state' => $state->value,
            'targetMonths' => $cushion->getTargetMonths(),
            'monthlyNetIncomeCents' => $monthlyIncomeCents,
            'referenceSalarySource' => $reference['source'],
            'targetCents' => $targetCents,
            'currentCents' => $currentCents,
            'peerClaimsCents' => $peerClaimsCents,
            'peerClaimObjects' => array_map(static fn (PeerDebt $debt) => $debt->getObject(), $claims),
            'deficitCents' => $deficitCents,
            'coveragePercent' => $targetCents > 0
                ? (int) round(max(0, min(100, $currentCents / $targetCents * 100)))
                : 0,
            'monthsCovered' => $monthlyIncomeCents > 0
                ? round(max(0, $currentCents) / $monthlyIncomeCents, 1)
                : 0.0,
            'rechargeCapCents' => $cushion->getRechargeCapCents(),
            'rechargeTargetMonths' => $cushion->getRechargeTargetMonths(),
            'monthlyRechargeCents' => $plan['monthlyRechargeCents'],
            'rechargeMonths' => $plan['rechargeMonths'],
            'isCappedByRechargeCap' => $plan['isCapped'],
            // What the score layer reads: no green while the net is not full.
            'blocksGreenScore' => CushionState::Complete !== $state,
            'isConfigured' => $monthlyIncomeCents > 0,
            'accounts' => array_map(static fn (Account $account) => [
                'id' => (string) $account->getId(),
                'name' => $account->getName(),
                'bank' => $account->effectiveBank(),
                'balanceCents' => $account->getBalanceCents(),
                'currency' => $account->getCurrency(),
            ], $accounts),
        ];
    }

    /**
     * Monthly effort to fill the deficit. The cap wins over the wished-for
     * horizon: when the two disagree, the recharge takes longer rather than
     * squeezing the budget harder.
     *
     * @return array{monthlyRechargeCents: int, rechargeMonths: int, isCapped: bool}
     */
    private function rechargePlan(SafetyCushion $cushion, int $deficitCents): array
    {
        if (0 === $deficitCents) {
            return ['monthlyRechargeCents' => 0, 'rechargeMonths' => 0, 'isCapped' => false];
        }

        $wanted = (int) ceil($deficitCents / $cushion->getRechargeTargetMonths());
        $cap = $cushion->getRechargeCapCents();

        if ($cap <= 0) {
            return [
                'monthlyRechargeCents' => $wanted,
                'rechargeMonths' => $cushion->getRechargeTargetMonths(),
                'isCapped' => false,
            ];
        }

        $monthly = min($wanted, $cap);

        return [
            'monthlyRechargeCents' => $monthly,
            'rechargeMonths' => (int) ceil($deficitCents / $monthly),
            'isCapped' => $wanted > $cap,
        ];
    }

    private function createFor(User $user): SafetyCushion
    {
        $cushion = (new SafetyCushion())->setUser($user);
        $this->em->persist($cushion);
        $this->em->flush();

        return $cushion;
    }
}
