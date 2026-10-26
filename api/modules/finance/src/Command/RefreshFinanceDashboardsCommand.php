<?php

declare(strict_types=1);

namespace Maggie\Finance\Command;

use Maggie\Core\Repository\UserRepository;
use Maggie\Finance\Message\MarkFinanceDashboardStaleCommand;
use Maggie\Finance\Repository\FinanceDashboardSnapshotRepository;
use Maggie\Finance\UseCase\GetFinanceDashboard;
use Maggie\Finance\UseCase\GetFinanceScores;
use Symfony\Component\Console\Attribute\AsCommand;
use Symfony\Component\Console\Command\Command;
use Symfony\Component\Console\Input\InputInterface;
use Symfony\Component\Console\Output\OutputInterface;
use Symfony\Component\Console\Style\SymfonyStyle;
use Symfony\Component\Messenger\MessageBusInterface;

#[AsCommand(
    name: 'app:finance:refresh-dashboards',
    description: 'Rebuild the Finance Dashboard read model of the users whose computed day is no longer their today or the server today, or whose payload an earlier release stored',
)]
class RefreshFinanceDashboardsCommand extends Command
{
    public function __construct(
        private readonly FinanceDashboardSnapshotRepository $snapshots,
        private readonly UserRepository $users,
        private readonly GetFinanceScores $getFinanceScores,
        private readonly MessageBusInterface $messageBus,
    ) {
        parent::__construct();
    }

    protected function execute(InputInterface $input, OutputInterface $output): int
    {
        $io = new SymfonyStyle($input, $output);
        $marked = 0;
        $serverToday = (new \DateTimeImmutable('today'))->format('Y-m-d');
        // A deploy that changed the payload's shape outdates every stored one.
        $shape = (string) GetFinanceDashboard::PAYLOAD_SHAPE;

        foreach ($this->snapshots->computedDaysOfUsersWithAccounts() as $userId => $days) {
            $user = $this->users->find($userId);
            if (null === $user) {
                continue;
            }

            $today = $this->getFinanceScores->resolveDate($user, null)->format('Y-m-d');
            if ($days['computedOn'] === $today && $days['computedServerOn'] === $serverToday && $days['shape'] === $shape) {
                continue;
            }

            $this->messageBus->dispatch(new MarkFinanceDashboardStaleCommand((string) $user->getId()));
            ++$marked;
        }

        $io->success(sprintf('%d dashboard(s) to rebuild.', $marked));

        return Command::SUCCESS;
    }
}
