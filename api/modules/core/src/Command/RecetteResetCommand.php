<?php

declare(strict_types=1);

namespace Maggie\Core\Command;

use Maggie\Core\Repository\UserRepository;
use Maggie\Core\Service\AgentResetClient;
use Maggie\Core\Service\RecetteAccount;
use Maggie\Core\Service\UserDataEraser;
use Symfony\Component\Console\Attribute\AsCommand;
use Symfony\Component\Console\Command\Command;
use Symfony\Component\Console\Input\InputInterface;
use Symfony\Component\Console\Input\InputOption;
use Symfony\Component\Console\Output\OutputInterface;
use Symfony\Component\Console\Style\SymfonyStyle;
use Symfony\Contracts\HttpClient\Exception\ExceptionInterface;

/**
 * Wipes all the data of the Recette account (MAG-249), API and agent sides, and
 * keeps the account and its permission.
 *
 * The target is the RecetteAccount constant, by construction: there is no
 * argument and no option naming a user, so this command can never reach
 * another account. Run by hand, never scheduled.
 *
 * The agent goes first: if it fails nothing is deleted here, and a rerun is safe.
 */
#[AsCommand(
    name: 'app:recette:reset',
    description: 'Delete all the data of the Recette account (API and agent), keeping the account',
)]
final class RecetteResetCommand extends Command
{
    public function __construct(
        private readonly UserRepository $userRepository,
        private readonly UserDataEraser $eraser,
        private readonly AgentResetClient $agent,
    ) {
        parent::__construct();
    }

    protected function configure(): void
    {
        $this->addOption('dry-run', null, InputOption::VALUE_NONE, 'Count what would be deleted, delete nothing');
    }

    protected function execute(InputInterface $input, OutputInterface $output): int
    {
        $io = new SymfonyStyle($input, $output);
        $dryRun = (bool) $input->getOption('dry-run');

        $user = $this->userRepository->findOneBy(['email' => RecetteAccount::EMAIL]);
        if (null === $user) {
            $io->success('The Recette account does not exist yet: nothing to reset.');

            return Command::SUCCESS;
        }

        try {
            $agentCounts = $this->agent->reset((string) $user->getId(), $dryRun);
        } catch (ExceptionInterface $e) {
            $io->error('Agent reset failed, nothing deleted: '.$e->getMessage());

            return Command::FAILURE;
        }

        $apiCounts = $this->eraser->erase($user, $dryRun);

        $verb = $dryRun ? 'would be deleted' : 'deleted';
        foreach (['Agent' => $agentCounts, 'API' => $apiCounts] as $side => $counts) {
            $io->section(sprintf('%s: rows %s', $side, $verb));
            $io->table(['Table', 'Rows'], array_map(fn ($name, $n) => [$name, $n], array_keys($counts), $counts));
        }

        $io->success($dryRun ? 'Dry run: nothing deleted.' : 'Recette account reset.');

        return Command::SUCCESS;
    }
}
