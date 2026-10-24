<?php

declare(strict_types=1);

namespace Maggie\MealGrocery\Command;

use Maggie\Cookbook\Event\MealPassed;
use Maggie\MealGrocery\Repository\MealGroceryContributionRepository;
use Symfony\Component\Console\Attribute\AsCommand;
use Symfony\Component\Console\Command\Command;
use Symfony\Component\Console\Input\InputInterface;
use Symfony\Component\Console\Output\OutputInterface;
use Symfony\Component\Console\Style\SymfonyStyle;
use Symfony\Component\DependencyInjection\Attribute\Autowire;
use Symfony\Component\Messenger\MessageBusInterface;

#[AsCommand(
    name: 'maggie:cookbook:release-past-meal-groceries',
    description: 'Take the unbought ingredients of meals whose day has gone off the grocery lists',
)]
class ReleasePastMealGroceriesCommand extends Command
{
    public function __construct(
        private readonly MealGroceryContributionRepository $contributionRepository,
        #[Autowire(service: 'event.bus')]
        private readonly MessageBusInterface $eventBus,
    ) {
        parent::__construct();
    }

    protected function execute(InputInterface $input, OutputInterface $output): int
    {
        $io = new SymfonyStyle($input, $output);
        $failed = 0;
        $announced = 0;

        // Today where the owner lives, not where the server runs.
        $today = new \DateTimeImmutable(
            (new \DateTimeImmutable('now', new \DateTimeZone('Europe/Paris')))->format('Y-m-d'),
            new \DateTimeZone('UTC'),
        );

        foreach ($this->contributionRepository->findPassedMealIdsWithUnboughtLines($today) as $mealId) {
            // One meal failing must not keep the others on their lists.
            try {
                $this->eventBus->dispatch(new MealPassed($mealId));
                ++$announced;
            } catch (\Throwable $e) {
                ++$failed;
                $io->error(sprintf('Meal %s: %s', $mealId, $e->getMessage()));
            }
        }

        if (0 === $announced + $failed) {
            $io->info('No past meal holds shopping.');
        } else {
            $io->success(sprintf('%d past meal(s) released.', $announced));
        }

        return 0 === $failed ? Command::SUCCESS : Command::FAILURE;
    }
}
