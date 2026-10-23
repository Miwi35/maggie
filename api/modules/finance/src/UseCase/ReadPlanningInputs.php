<?php

declare(strict_types=1);

namespace Maggie\Finance\UseCase;

use Maggie\Core\Entity\User;
use Maggie\Finance\Entity\Account;
use Maggie\Finance\Entity\Project;
use Maggie\Finance\Enum\AccountType;
use Maggie\Finance\Enum\FundingSourceKind;
use Maggie\Finance\Repository\AccountRepository;
use Maggie\Finance\Repository\LoanRepository;
use Maggie\Finance\Repository\PlacementRepository;
use Maggie\Finance\Repository\ProjectRepository;
use Maggie\Finance\Service\PlanningInputs;
use Psr\Clock\ClockInterface;

/**
 * What the planning reads of the user's world (MAG-526): the net capacity, the
 * cushion, the projects, the savings reserve — plus the signed loans and the
 * placements the scenarios work on (MAG-550).
 *
 * The savings reserve is the balance of the savings accounts outside the
 * cushion, less what the projects' `savings` sources already claim on them.
 */
class ReadPlanningInputs
{
    public function __construct(
        private readonly GetDebtTimeline $getDebtTimeline,
        private readonly GetCushionStatus $getCushionStatus,
        private readonly ProjectRepository $projectRepository,
        private readonly AccountRepository $accountRepository,
        private readonly LoanRepository $loanRepository,
        private readonly PlacementRepository $placementRepository,
        private readonly ClockInterface $clock,
    ) {
    }

    public function execute(User $user): PlanningInputs
    {
        $capacity = $this->getDebtTimeline->execute($user, 1)['savingCapacity'];
        $cushion = $this->getCushionStatus->execute($user);
        $projects = $this->projectRepository->findByUser($user);

        return new PlanningInputs(
            \DateTimeImmutable::createFromInterface($this->clock->now()),
            (int) $capacity['netCapacityCents'],
            (bool) $capacity['isIncomeKnown'],
            [
                'state' => (string) $cushion['state'],
                'deficitCents' => (int) $cushion['deficitCents'],
                'monthlyRechargeCents' => (int) $cushion['monthlyRechargeCents'],
            ],
            $this->reserveCents($user, $projects),
            $projects,
            $this->loanRepository->findSignedByUser($user),
            $this->placementRepository->findByUser($user),
        );
    }

    /** @param list<Project> $projects */
    private function reserveCents(User $user, array $projects): int
    {
        $claimed = [];
        foreach ($projects as $project) {
            foreach ($project->getFundingSources() as $source) {
                if (FundingSourceKind::Savings === $source->getKind() && null !== $source->getAccountId()) {
                    $claimed[$source->getAccountId()] = ($claimed[$source->getAccountId()] ?? 0) + $source->getAmountCents();
                }
            }
        }

        $reserve = 0;
        foreach ($this->accountRepository->findByUser($user) as $account) {
            if (!$this->isReserve($account)) {
                continue;
            }
            $reserve += max(0, $account->getBalanceCents() - ($claimed[(string) $account->getId()] ?? 0));
        }

        return $reserve;
    }

    private function isReserve(Account $account): bool
    {
        return AccountType::Savings === $account->getType() && !$account->isCushion() && !$account->isClosed();
    }
}
