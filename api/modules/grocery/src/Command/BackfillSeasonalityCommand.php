<?php

declare(strict_types=1);

namespace Maggie\Grocery\Command;

use Doctrine\ORM\EntityManagerInterface;
use Maggie\Core\Mercure\EntityBroadcaster;
use Maggie\Grocery\Entity\Product;
use Maggie\Grocery\Service\SeasonalityCatalog;
use Symfony\Component\Console\Attribute\AsCommand;
use Symfony\Component\Console\Command\Command;
use Symfony\Component\Console\Input\InputInterface;
use Symfony\Component\Console\Input\InputOption;
use Symfony\Component\Console\Output\OutputInterface;
use Symfony\Component\Console\Style\SymfonyStyle;

/**
 * Gives the products created before the seasonality (MAG-494) their season
 * months from the calendar. Only a product without months is touched: a value
 * the user typed or corrected stays as it is. Safe to run twice.
 */
#[AsCommand(
    name: 'app:grocery:seasonality:backfill',
    description: 'Fill the season months of the products that have none, from the seasonality calendar',
)]
class BackfillSeasonalityCommand extends Command
{
    public function __construct(
        private readonly EntityManagerInterface $em,
        private readonly SeasonalityCatalog $catalog,
        private readonly EntityBroadcaster $broadcaster,
    ) {
        parent::__construct();
    }

    protected function configure(): void
    {
        $this->addOption('dry-run', null, InputOption::VALUE_NONE, 'List what would be filled, change nothing');
    }

    protected function execute(InputInterface $input, OutputInterface $output): int
    {
        $io = new SymfonyStyle($input, $output);
        $dryRun = (bool) $input->getOption('dry-run');

        /** @var list<Product> $products */
        $products = $this->em->getRepository(Product::class)->createQueryBuilder('p')
            ->where('p.seasonMonths IS NULL')
            ->getQuery()
            ->getResult();

        $filled = [];
        foreach ($products as $product) {
            $months = $this->catalog->monthsForProduct($product);
            if (null === $months) {
                continue;
            }

            $io->writeln(sprintf('%s « %s » : %s', $product->getId(), $product->getName(), implode(', ', $months)), OutputInterface::VERBOSITY_VERBOSE);
            if (!$dryRun) {
                $product->setSeasonMonths($months);
            }
            $filled[] = $product;
        }

        if ($dryRun) {
            $io->info(sprintf('%d product(s) would get their season months.', \count($filled)));

            return Command::SUCCESS;
        }

        $this->em->flush();

        // A flush outside the bus publishes nothing: the screens and the index learn it here.
        foreach ($filled as $product) {
            $this->broadcaster->broadcast($product);
        }

        $io->success(sprintf('%d product(s) got their season months.', \count($filled)));

        return Command::SUCCESS;
    }
}
