<?php

declare(strict_types=1);

namespace Maggie\Core\E2e\Command;

use Maggie\Core\E2e\Coverage\JourneyCoverageMerger;
use Symfony\Component\Console\Attribute\AsCommand;
use Symfony\Component\Console\Command\Command;
use Symfony\Component\Console\Input\InputInterface;
use Symfony\Component\Console\Input\InputOption;
use Symfony\Component\Console\Output\OutputInterface;

/**
 * Turns the coverage the API recorded per request into one raw file per journey (contract 2 of
 * the spec « Sélection e2e par couverture »). Called by `task e2e:coverage:collect`, which copies
 * the result to e2e/coverage/raw/api/ (scripts/e2e/coverage/README.md).
 */
#[AsCommand(
    name: 'app:e2e:coverage:merge',
    description: 'Merge the per-request e2e coverage into one raw file per journey',
)]
final class E2eCoverageMergeCommand extends Command
{
    public function __construct(
        private readonly JourneyCoverageMerger $merger,
        private readonly string $coverageDir,
    ) {
        parent::__construct();
    }

    protected function configure(): void
    {
        $this->addOption('output', null, InputOption::VALUE_REQUIRED, 'Directory of the raw files', null);
    }

    protected function execute(InputInterface $input, OutputInterface $output): int
    {
        $target = $input->getOption('output');
        $target = \is_string($target) && '' !== $target ? $target : $this->coverageDir.'/raw/api';

        $count = $this->merger->merge($this->coverageDir.'/requests', $target);
        $output->writeln(\sprintf('api coverage: %d journeys written to %s', $count, $target));

        return Command::SUCCESS;
    }
}
