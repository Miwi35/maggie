<?php

declare(strict_types=1);

namespace Maggie\MealGrocery\Command;

use Doctrine\ORM\EntityManagerInterface;
use Maggie\Grocery\Entity\GroceryList;
use Maggie\MealGrocery\Message\ConsolidateGroceryListCommand;
use Maggie\MealGrocery\Service\GroceryLineConsolidator;
use Symfony\Component\Console\Attribute\AsCommand;
use Symfony\Component\Console\Command\Command;
use Symfony\Component\Console\Input\InputInterface;
use Symfony\Component\Console\Input\InputOption;
use Symfony\Component\Console\Output\OutputInterface;
use Symfony\Component\Console\Style\SymfonyStyle;
use Symfony\Component\Messenger\MessageBusInterface;
use Symfony\Component\Messenger\Stamp\HandledStamp;

#[AsCommand(
    name: 'maggie:grocery:consolidate-lines',
    description: 'Merge the duplicate lines of each product on the grocery lists and work their totals out again from the meals',
)]
class ConsolidateGroceryLinesCommand extends Command
{
    public function __construct(
        private readonly EntityManagerInterface $em,
        private readonly GroceryLineConsolidator $consolidator,
        private readonly MessageBusInterface $commandBus,
    ) {
        parent::__construct();
    }

    protected function configure(): void
    {
        $this->addOption('dry-run', null, InputOption::VALUE_NONE, 'List what would change without touching anything');
    }

    protected function execute(InputInterface $input, OutputInterface $output): int
    {
        $io = new SymfonyStyle($input, $output);
        $dryRun = (bool) $input->getOption('dry-run');
        $products = 0;
        $failed = 0;

        /** @var GroceryList[] $lists */
        $lists = $this->em->getRepository(GroceryList::class)->findAll();

        foreach ($lists as $list) {
            // One list failing must not keep the others as they were.
            try {
                if ($dryRun) {
                    $plan = $this->consolidator->plan($list);
                    foreach ($plan as $entry) {
                        $io->writeln(sprintf('%s: %d line(s) [%s] => %s', $entry['product'], $entry['lines'], $entry['before'], $entry['after']));
                    }
                    $products += count($plan);

                    continue;
                }

                $envelope = $this->commandBus->dispatch(new ConsolidateGroceryListCommand((string) $list->getId()));
                $handled = $envelope->last(HandledStamp::class);
                $products += null === $handled ? 0 : (int) $handled->getResult();
            } catch (\Throwable $e) {
                ++$failed;
                $io->error(sprintf('List %s: %s', $list->getId(), $e->getMessage()));
            }
        }

        if (0 === $products) {
            $io->info('Every product already has one line.');
        } else {
            $io->success(sprintf($dryRun ? '%d product(s) would be merged or recounted.' : '%d product(s) merged or recounted.', $products));
        }

        return 0 === $failed ? Command::SUCCESS : Command::FAILURE;
    }
}
