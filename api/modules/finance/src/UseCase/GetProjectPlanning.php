<?php

declare(strict_types=1);

namespace Maggie\Finance\UseCase;

use Maggie\Core\Entity\User;
use Maggie\Finance\Entity\Account;
use Maggie\Finance\Entity\Project;
use Maggie\Finance\Enum\AccountType;
use Maggie\Finance\Enum\FundingSourceKind;
use Maggie\Finance\Repository\AccountRepository;
use Maggie\Finance\Repository\ProjectRepository;
use Maggie\Finance\Service\ProjectPlanner;
use Psr\Clock\ClockInterface;

/**
 * The user's projects over the next 36 months (MAG-526): capacity first, the
 * safety cushion first. Recomputed on every read, never stored — the clients
 * read it again when a project, a loan, the cushion or an account is published.
 *
 * `$overspentCents` is what the month under way already went over: it lowers
 * the capacity of this month only, so the planning shifts without blame.
 *
 * The savings reserve is the balance of the savings accounts outside the
 * cushion, less what the projects' `savings` sources already claim on them.
 */
class GetProjectPlanning
{
    public function __construct(
        private readonly GetDebtTimeline $getDebtTimeline,
        private readonly GetCushionStatus $getCushionStatus,
        private readonly ProjectRepository $projectRepository,
        private readonly AccountRepository $accountRepository,
        private readonly ProjectPlanner $planner,
        private readonly ClockInterface $clock,
    ) {
    }

    /** @return array<string, mixed> */
    public function execute(User $user, int $overspentCents = 0): array
    {
        $capacity = $this->getDebtTimeline->execute($user, 1)['savingCapacity'];
        $cushion = $this->getCushionStatus->execute($user);
        $projects = $this->projectRepository->findByUser($user);

        $plan = $this->planner->plan(
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
            max(0, $overspentCents),
        );

        return [
            'startMonth' => $plan['startMonth'],
            'horizonMonths' => $plan['horizonMonths'],
            'currency' => ($projects[0] ?? null)?->getCurrency() ?? 'EUR',
            'netCapacityCents' => $plan['netCapacityCents'],
            'isIncomeKnown' => $plan['isIncomeKnown'],
            'cushion' => $plan['cushion'],
            'months' => $plan['months'],
            'projects' => $plan['projects'],
        ];
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
