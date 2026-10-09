<?php

declare(strict_types=1);

namespace Maggie\Finance\Command;

use Maggie\Core\Repository\UserRepository;
use Maggie\Finance\UseCase\DetectRejections;
use Symfony\Component\Console\Attribute\AsCommand;
use Symfony\Component\Console\Command\Command;
use Symfony\Component\Console\Input\InputInterface;
use Symfony\Component\Console\Input\InputOption;
use Symfony\Component\Console\Output\OutputInterface;
use Symfony\Component\Console\Style\SymfonyStyle;

#[AsCommand(
    name: 'app:finance:detect-rejections',
    description: 'Pair the payments the bank rejected with the credits that gave them back',
)]
class DetectRejectionsCommand extends Command
{
    public function __construct(
        private readonly UserRepository $userRepository,
        private readonly DetectRejections $detectRejections,
    ) {
        parent::__construct();
    }

    protected function configure(): void
    {
        $this
            ->addOption('dry-run', null, InputOption::VALUE_NONE, 'Only report what would be paired, write nothing')
            ->addOption('days', null, InputOption::VALUE_REQUIRED, 'Only look at the credits of the last days (the whole history by default)')
            ->setHelp(<<<'HELP'
                Until MAG-350, a payment the bank rejected counted twice: its debit
                as an expense, the credit giving it back as an income.

                This pairs each credit worded as a rejection (REJET, IMPAYE,
                RETOUR PRLV…) with the debit of the same account, of the same
                amount, at most ten days before, whose payee matches. Both lines
                then leave every figure, and a recent rejection raises one
                notification. A credit without its debit is listed and left as it
                is. Running it twice changes nothing more.

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

        $matched = 0;
        foreach ($this->userRepository->findAll() as $user) {
            $result = $this->detectRejections->execute($user, $limitDays, $dryRun);
            $matched += $result['matched'];

            if ([] !== $result['pairs']) {
                $io->section($user->getEmail());
                $io->table(
                    ['Débit', 'Rejet', 'Montant'],
                    array_map(static fn (array $pair) => [
                        sprintf('%s %s', $pair['bookedAt'], $pair['label']),
                        sprintf('%s %s', $pair['counterpartBookedAt'], $pair['counterpartLabel']),
                        sprintf('%.2f', abs($pair['amountCents']) / 100),
                    ], $result['pairs']),
                );
            }

            if ([] !== $result['unmatched']) {
                $io->warning(array_merge(
                    [sprintf('%s — rejets sans le débit qu’ils rendent, laissés tels quels :', $user->getEmail())],
                    array_map(
                        static fn (array $line) => sprintf('%s %s %.2f', $line['bookedAt'], $line['label'], $line['amountCents'] / 100),
                        $result['unmatched'],
                    ),
                ));
            }
        }

        if ($dryRun) {
            $io->note(sprintf('Dry run only — %d rejet(s) would be paired, nothing was written.', $matched));

            return Command::SUCCESS;
        }

        $io->success(sprintf('%d rejet(s) apparié(s).', $matched));

        return Command::SUCCESS;
    }
}
