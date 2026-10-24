<?php

declare(strict_types=1);

namespace Maggie\Finance\Home;

use Maggie\Core\Entity\User;
use Maggie\Core\Home\HomeCardProviderInterface;
use Maggie\Core\Home\HomeCardWatcherInterface;
use Maggie\Core\Home\HomeDay;
use Maggie\Finance\Entity\Account;
use Maggie\Finance\Entity\Category;
use Maggie\Finance\Entity\Envelope;
use Maggie\Finance\Entity\Loan;
use Maggie\Finance\Entity\Placement;
use Maggie\Finance\Entity\Project;
use Maggie\Finance\Entity\RecurringOperation;
use Maggie\Finance\Entity\SafetyCushion;
use Maggie\Finance\Entity\Scenario;
use Maggie\Finance\Entity\Transaction;
use Maggie\Finance\Entity\TreasurySettings;
use Maggie\Finance\Repository\AccountRepository;
use Maggie\Finance\Repository\EnvelopeRepository;
use Maggie\Finance\Repository\TransactionRepository;
use Maggie\Finance\UseCase\GetFinanceScores;
use Maggie\Finance\UseCase\GetProjectPlanning;

/**
 * The Finance tile of the home page (MAG-565): the week and the month as two
 * rings with their rhythm status, the lines still to qualify, and the next
 * project of the current scenario. It reads the use cases the Finance
 * Dashboard is made of, so both tell the same figures. It does not move with
 * « Aujourd'hui · Demain ».
 */
class FinanceHomeCard implements HomeCardProviderInterface, HomeCardWatcherInterface
{
    public function __construct(
        private readonly GetFinanceScores $getFinanceScores,
        private readonly GetProjectPlanning $getProjectPlanning,
        private readonly TransactionRepository $transactionRepository,
        private readonly AccountRepository $accountRepository,
        private readonly EnvelopeRepository $envelopeRepository,
    ) {
    }

    public function module(): string
    {
        return 'finance';
    }

    public function watchedEntities(): array
    {
        return [
            Transaction::class,
            Envelope::class,
            Category::class,
            RecurringOperation::class,
            Project::class,
            Scenario::class,
            Account::class,
            SafetyCushion::class,
            Loan::class,
            Placement::class,
            TreasurySettings::class,
        ];
    }

    public function state(User $user, HomeDay $day): array
    {
        $scores = $this->getFinanceScores->execute($user, $this->getFinanceScores->resolveDate($user, null));

        return [
            'week' => self::ring($scores['week']['rhythm']),
            'month' => self::ring($scores['month']['rhythm']),
            'toQualifyCount' => $this->transactionRepository->countToSortForUser($user),
            'nextProject' => $this->nextProject($user),
            // Without an account nor an envelope the card has nothing to say: the clients leave it out (MAG-594).
            'accountCount' => $this->accountRepository->count(['user' => $user]),
            'envelopeCount' => $this->envelopeRepository->count(['user' => $user]),
        ];
    }

    /**
     * @param array<string, mixed> $rhythm
     *
     * @return array{status: string, label: string, budgetCents: int, remainingCents: int, expectedRemainingCents: int}
     */
    private static function ring(array $rhythm): array
    {
        return [
            'status' => $rhythm['status'],
            'label' => $rhythm['label'],
            'budgetCents' => $rhythm['budgetCents'],
            'remainingCents' => $rhythm['remainingCents'],
            'expectedRemainingCents' => $rhythm['expectedRemainingCents'],
        ];
    }

    /**
     * The first project the planning lands inside its horizon; null with none.
     *
     * @return array{projectId: string, name: string, amountCents: int, gatheredCents: int, purchaseMonth: string}|null
     */
    private function nextProject(User $user): ?array
    {
        foreach ($this->getProjectPlanning->execute($user)['projects'] as $project) {
            if (null !== $project['purchaseMonth']) {
                return [
                    'projectId' => $project['projectId'],
                    'name' => $project['name'],
                    'amountCents' => $project['amountCents'],
                    'gatheredCents' => $project['gatheredCents'],
                    'purchaseMonth' => $project['purchaseMonth'],
                ];
            }
        }

        return null;
    }
}
