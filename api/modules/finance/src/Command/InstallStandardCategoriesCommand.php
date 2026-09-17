<?php

declare(strict_types=1);

namespace Maggie\Finance\Command;

use Maggie\Core\Repository\UserRepository;
use Maggie\Finance\UseCase\InstallStandardCategories;
use Symfony\Component\Console\Attribute\AsCommand;
use Symfony\Component\Console\Command\Command;
use Symfony\Component\Console\Input\InputArgument;
use Symfony\Component\Console\Input\InputInterface;
use Symfony\Component\Console\Output\OutputInterface;
use Symfony\Component\Console\Style\SymfonyStyle;

#[AsCommand(
    name: 'app:finance:categories:standard',
    description: 'Create the categories a budget starts from, for one user',
)]
class InstallStandardCategoriesCommand extends Command
{
    public function __construct(
        private readonly UserRepository $userRepository,
        private readonly InstallStandardCategories $installStandardCategories,
    ) {
        parent::__construct();
    }

    protected function configure(): void
    {
        $this
            ->addArgument('email', InputArgument::REQUIRED, 'Email of the account owner')
            ->setHelp(<<<'HELP'
                Lays down a starting set of categories: housing, food,
                subscriptions and their utilities, leisure, investments, fuel
                and unexpected costs.

                Safe to run twice — a heading already there is left untouched,
                whatever was done to it since.

                  <info>%command.full_name% someone@example.com</info>
                HELP);
    }

    protected function execute(InputInterface $input, OutputInterface $output): int
    {
        $io = new SymfonyStyle($input, $output);
        $email = (string) $input->getArgument('email');

        $user = $this->userRepository->findOneBy(['email' => $email]);
        if ($user === null) {
            $io->error(sprintf('No user with email "%s".', $email));

            return Command::FAILURE;
        }

        $result = $this->installStandardCategories->execute($user);

        if ($result['created'] === 0) {
            $io->success('Toutes les catégories de départ étaient déjà là.');

            return Command::SUCCESS;
        }

        $io->listing($result['names']);
        $io->success(sprintf(
            '%d catégorie(s) créée(s), %d déjà présente(s).',
            $result['created'],
            $result['kept'],
        ));

        return Command::SUCCESS;
    }
}
