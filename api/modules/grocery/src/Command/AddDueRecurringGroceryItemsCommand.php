<?php

declare(strict_types=1);

namespace Maggie\Grocery\Command;

use Maggie\Grocery\Event\RecurringGroceryItemDue;
use Maggie\Grocery\Repository\RecurringGroceryItemRepository;
use Maggie\Grocery\Specification\IsRecurringGroceryItemDue;
use Symfony\Component\Console\Attribute\AsCommand;
use Symfony\Component\Console\Command\Command;
use Symfony\Component\Console\Input\InputInterface;
use Symfony\Component\Console\Output\OutputInterface;
use Symfony\Component\Console\Style\SymfonyStyle;
use Symfony\Component\DependencyInjection\Attribute\Autowire;
use Symfony\Component\Messenger\MessageBusInterface;

#[AsCommand(
    name: 'maggie:grocery:add-due-recurring-items',
    description: 'Put the recurring grocery items that have come due on their owner\'s list',
)]
class AddDueRecurringGroceryItemsCommand extends Command
{
    public function __construct(
        private readonly RecurringGroceryItemRepository $recurringGroceryItemRepository,
        #[Autowire(service: 'event.bus')]
        private readonly MessageBusInterface $eventBus,
    ) {
        parent::__construct();
    }

    protected function execute(InputInterface $input, OutputInterface $output): int
    {
        $io = new SymfonyStyle($input, $output);
        $due = IsRecurringGroceryItemDue::today();
        $failed = 0;
        $announced = 0;

        foreach ($this->recurringGroceryItemRepository->findAllForAllUsers() as $item) {
            if (!$due->isSatisfiedBy($item)) {
                continue;
            }

            // One item failing must not keep the others off their lists.
            try {
                $this->eventBus->dispatch(new RecurringGroceryItemDue((string) $item->getId()));
                ++$announced;
            } catch (\Throwable $e) {
                ++$failed;
                $io->error(sprintf('Recurring item %s: %s', $item->getId(), $e->getMessage()));
            }
        }

        if (0 === $announced + $failed) {
            $io->info('No recurring item is due.');
        } else {
            $io->success(sprintf('%d recurring item(s) handled.', $announced));
        }

        return 0 === $failed ? Command::SUCCESS : Command::FAILURE;
    }
}
