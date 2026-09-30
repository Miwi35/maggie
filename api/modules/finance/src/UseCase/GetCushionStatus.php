<?php

declare(strict_types=1);

namespace Maggie\Finance\UseCase;

use Doctrine\ORM\EntityManagerInterface;
use Maggie\Core\Entity\User;
use Maggie\Finance\Entity\Account;
use Maggie\Finance\Entity\SafetyCushion;
use Maggie\Finance\Enum\CushionState;
use Maggie\Finance\Repository\AccountRepository;
use Maggie\Finance\Repository\SafetyCushionRepository;

/**
 * Where the safety net stands, and what it would take to fill it back up.
 *
 * The current amount is never stored: it is the balance of the accounts
 * flagged as cushion, so the net follows the accounts without double entry.
 */
class GetCushionStatus
{
    public function __construct(
        private readonly SafetyCushionRepository $cushionRepository,
        private readonly AccountRepository $accountRepository,
        private readonly EntityManagerInterface $em,
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

        $currentCents = array_sum(array_map(
            static fn (Account $account) => $account->getBalanceCents(),
            $accounts,
        ));

        $targetCents = $cushion->getTargetCents();
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
            'monthlyNetIncomeCents' => $cushion->getMonthlyNetIncomeCents(),
            'targetCents' => $targetCents,
            'currentCents' => $currentCents,
            'deficitCents' => $deficitCents,
            'coveragePercent' => $targetCents > 0
                ? (int) round(min(100, $currentCents / $targetCents * 100))
                : 0,
            'monthsCovered' => $cushion->getMonthlyNetIncomeCents() > 0
                ? round($currentCents / $cushion->getMonthlyNetIncomeCents(), 1)
                : 0.0,
            'rechargeCapCents' => $cushion->getRechargeCapCents(),
            'rechargeTargetMonths' => $cushion->getRechargeTargetMonths(),
            'monthlyRechargeCents' => $plan['monthlyRechargeCents'],
            'rechargeMonths' => $plan['rechargeMonths'],
            'isCappedByRechargeCap' => $plan['isCapped'],
            // What the score layer reads: no green while the net is not full.
            'blocksGreenScore' => CushionState::Complete !== $state,
            'isConfigured' => $cushion->getMonthlyNetIncomeCents() > 0,
            'accounts' => array_map(static fn (Account $account) => [
                'id' => (string) $account->getId(),
                'name' => $account->getName(),
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
