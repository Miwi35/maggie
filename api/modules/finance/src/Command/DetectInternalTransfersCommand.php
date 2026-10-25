<?php

declare(strict_types=1);

namespace Maggie\Finance\Command;

use Maggie\Core\Repository\UserRepository;
use Maggie\Finance\UseCase\DetectInternalTransfers;
use Symfony\Component\Console\Attribute\AsCommand;
use Symfony\Component\Console\Command\Command;
use Symfony\Component\Console\Input\InputInterface;
use Symfony\Component\Console\Input\InputOption;
use Symfony\Component\Console\Output\OutputInterface;
use Symfony\Component\Console\Style\SymfonyStyle;

#[AsCommand(
    name: 'app:finance:detect-internal-transfers',
    description: 'Pair the movements between the owners\' own accounts and mark the lone legs that name the owner',
)]
class DetectInternalTransfersCommand extends Command
{
    public function __construct(
        private readonly UserRepository $userRepository,
        private readonly DetectInternalTransfers $detectInternalTransfers,
    ) {
        parent::__construct();
    }

    protected function configure(): void
    {
        $this
            ->addOption('dry-run', null, InputOption::VALUE_NONE, 'Only report what would change, write nothing')
            ->addOption('days', null, InputOption::VALUE_REQUIRED, 'Only look at the last days (the whole history by default)')
            ->setHelp(<<<'HELP'
                Catch-up over the history for a rule that learned to recognise more
                of the owner's own movements (MAG-642: a debit whose label is the
                holder's name alone, as N26 words a transfer to oneself).

                Pairs the two legs of each movement between two accounts, and marks
                as internal the lines left alone that name the owner or one of his
                banks. A line already categorised, qualified or judged by hand is
                never touched. Every figure that reads them (lifestyle, savings
                capacity) and the Finance Dashboard follow on their own. Running it
                twice changes nothing more.

                  <info>%command.full_name% --dry-run</info>
                  <info>%command.full_name%</info>
                HELP);
    }

    protected function execute(InputInterface $input, OutputInterface $output): int
    {
        $io = new SymfonyStyle($input, $output);
        $dryRun = (bool) $input->getOption('dry-run');

        $days = $input->getOption('days');
        if (null !== $days && !ctype_digit((string) $days)) {
            $io->error('--days must be a positive integer.');

            return Command::INVALID;
        }
        $limitDays = null === $days ? null : (int) $days;

        $pairs = 0;
        $loneLegs = 0;
        foreach ($this->userRepository->findAll() as $user) {
            $result = $this->detectInternalTransfers->execute($user, $limitDays, $dryRun);
            $pairs += $result['matched'];
            $loneLegs += \count($result['loneLegs']);

            if ([] === $result['pairs'] && [] === $result['loneLegs']) {
                continue;
            }

            $io->section($user->getEmail());
            if ([] !== $result['pairs']) {
                $io->table(
                    ['Sortie', 'Entrée', 'Montant'],
                    array_map(static fn (array $pair) => [
                        sprintf('%s %s', $pair['bookedAt'], $pair['label']),
                        sprintf('%s %s', $pair['counterpartBookedAt'], $pair['counterpartLabel']),
                        sprintf('%.2f', abs($pair['amountCents']) / 100),
                    ], $result['pairs']),
                );
            }
            if ([] !== $result['loneLegs']) {
                $io->table(
                    ['Ligne seule', 'Montant', 'Raison'],
                    array_map(static fn (array $leg) => [
                        sprintf('%s %s', $leg['bookedAt'], $leg['label']),
                        sprintf('%.2f', $leg['amountCents'] / 100),
                        $leg['reason'],
                    ], $result['loneLegs']),
                );
            }
        }

        if ($dryRun) {
            $io->note(sprintf('Dry run only — %d pair(s) and %d lone leg(s) would be marked, nothing was written.', $pairs, $loneLegs));

            return Command::SUCCESS;
        }

        $io->success(sprintf('%d pair(s) and %d lone leg(s) marked.', $pairs, $loneLegs));

        return Command::SUCCESS;
    }
}
