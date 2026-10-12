<?php

declare(strict_types=1);

namespace Maggie\Finance\Command;

use Maggie\Core\Repository\UserRepository;
use Maggie\Finance\Message\TagStandardCategoriesCommand as TagStandardCategoriesMessage;
use Symfony\Component\Console\Attribute\AsCommand;
use Symfony\Component\Console\Command\Command;
use Symfony\Component\Console\Input\InputArgument;
use Symfony\Component\Console\Input\InputInterface;
use Symfony\Component\Console\Output\OutputInterface;
use Symfony\Component\Console\Style\SymfonyStyle;
use Symfony\Component\Messenger\MessageBusInterface;
use Symfony\Component\Messenger\Stamp\HandledStamp;

#[AsCommand(
    name: 'app:finance:categories:tag-standard',
    description: 'Give their standard key to the categories made by hand before it existed (« Salaire », « Primes »)',
)]
class TagStandardCategoriesCommand extends Command
{
    public function __construct(
        private readonly UserRepository $userRepository,
        private readonly MessageBusInterface $bus,
    ) {
        parent::__construct();
    }

    protected function configure(): void
    {
        $this
            ->addArgument('email', InputArgument::OPTIONAL, 'Email of one account owner; every account when left out')
            ->setHelp(<<<'HELP'
                Finds, for each standard key no category holds yet, the
                top-level category with the standard name — « Salaire »,
                « Primes », any casing — and gives it the key. Nothing is
                created, renamed or recoloured.

                Safe to run twice: a key already held is left where it is.

                  <info>%command.full_name%</info>
                  <info>%command.full_name% someone@example.com</info>
                HELP);
    }

    protected function execute(InputInterface $input, OutputInterface $output): int
    {
        $io = new SymfonyStyle($input, $output);
        $email = $input->getArgument('email');

        if (\is_string($email)) {
            $user = $this->userRepository->findOneBy(['email' => $email]);
            if (null === $user) {
                $io->error(sprintf('No user with email "%s".', $email));

                return Command::FAILURE;
            }
            $users = [$user];
        } else {
            $users = $this->userRepository->findAll();
        }

        $count = 0;
        foreach ($users as $user) {
            // Through the bus: the projection publishes and reindexes what changed.
            $tagged = $this->bus->dispatch(new TagStandardCategoriesMessage((string) $user->getId()))
                ->last(HandledStamp::class)?->getResult();
            foreach (\is_array($tagged) ? $tagged : [] as $key => $name) {
                $io->writeln(sprintf('%s : « %s » → %s', $user->getEmail(), $name, $key));
                ++$count;
            }
        }

        $io->success(0 === $count ? 'Aucune catégorie à marquer.' : sprintf('%d catégorie(s) marquée(s).', $count));

        return Command::SUCCESS;
    }
}
