<?php

declare(strict_types=1);

namespace Maggie\Proaction\Command;

use Maggie\Proaction\Entity\ProactionStatus;
use Maggie\Proaction\Message\ExecuteProactionMessage;
use Maggie\Proaction\Repository\ProactionRepository;
use Doctrine\ORM\EntityManagerInterface;
use Symfony\Component\Console\Attribute\AsCommand;
use Symfony\Component\Console\Command\Command;
use Symfony\Component\Console\Input\InputInterface;
use Symfony\Component\Console\Output\OutputInterface;
use Symfony\Component\Console\Style\SymfonyStyle;
use Symfony\Component\Messenger\MessageBusInterface;

#[AsCommand(
    name: 'maggie:proaction:run',
    description: 'Execute due proactions (scheduledAt <= now, status = pending)',
)]
class RunProactionsCommand extends Command
{
    public function __construct(
        private readonly ProactionRepository $proactionRepository,
        private readonly MessageBusInterface $messageBus,
        private readonly EntityManagerInterface $em,
    ) {
        parent::__construct();
    }

    protected function execute(InputInterface $input, OutputInterface $output): int
    {
        $io = new SymfonyStyle($input, $output);

        $dueProactions = $this->proactionRepository->findDueProactions();

        if (count($dueProactions) === 0) {
            $io->info('No due proactions found.');
            return Command::SUCCESS;
        }

        $io->info(sprintf('Found %d due proaction(s).', count($dueProactions)));

        foreach ($dueProactions as $proaction) {
            $proaction->setStatus(ProactionStatus::Running);
            $this->em->flush();

            $this->messageBus->dispatch(new ExecuteProactionMessage(
                proactionId: (string) $proaction->getId(),
            ));

            $io->writeln(sprintf(
                '  Dispatched proaction %s: %s',
                $proaction->getId(),
                mb_substr($proaction->getPrompt(), 0, 80),
            ));
        }

        $io->success(sprintf('Dispatched %d proaction(s).', count($dueProactions)));

        return Command::SUCCESS;
    }
}
