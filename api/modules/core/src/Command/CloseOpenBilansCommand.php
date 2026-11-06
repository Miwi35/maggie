<?php

declare(strict_types=1);

namespace Maggie\Core\Command;

use Maggie\Core\Enum\Activity;
use Maggie\Core\Message\DeleteActivityStatusCommand;
use Maggie\Core\Repository\ActivityStatusRepository;
use Symfony\Component\Console\Attribute\AsCommand;
use Symfony\Component\Console\Command\Command;
use Symfony\Component\Console\Input\InputInterface;
use Symfony\Component\Console\Output\OutputInterface;
use Symfony\Component\Console\Style\SymfonyStyle;
use Symfony\Component\Messenger\MessageBusInterface;

/**
 * « Bilan du soir » left open (MAG-747): it stays resumable until the next
 * morning, then is closed. The answers were saved with each step, so closing
 * only removes the status; the day of a bilan is the Paris day it was started
 * for, and today's bilan is left alone.
 */
#[AsCommand(
    name: 'maggie:core:close-open-bilans',
    description: 'Close the evening reviews of a day that has gone',
)]
final class CloseOpenBilansCommand extends Command
{
    public function __construct(
        private readonly ActivityStatusRepository $repository,
        private readonly MessageBusInterface $bus,
    ) {
        parent::__construct();
    }

    protected function execute(InputInterface $input, OutputInterface $output): int
    {
        $io = new SymfonyStyle($input, $output);
        $today = (new \DateTimeImmutable('now', new \DateTimeZone('Europe/Paris')))->format('Y-m-d');

        $closed = 0;
        $failed = 0;
        foreach ($this->repository->findByActivityWithOriginIdBefore(Activity::Bilan, $today) as $status) {
            try {
                $this->bus->dispatch(new DeleteActivityStatusCommand((string) $status->getUser()->getId(), Activity::Bilan));
                ++$closed;
            } catch (\Throwable $e) {
                ++$failed;
                $io->error(\sprintf('Bilan %s: %s', $status->getId(), $e->getMessage()));
            }
        }

        $io->info(\sprintf('%d bilan(s) closed.', $closed));

        return 0 === $failed ? Command::SUCCESS : Command::FAILURE;
    }
}
