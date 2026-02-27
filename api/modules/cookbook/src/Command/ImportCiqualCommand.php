<?php

declare(strict_types=1);

namespace Maggie\Cookbook\Command;

use Maggie\Cookbook\Service\CiqualImporter;
use Symfony\Component\Console\Attribute\AsCommand;
use Symfony\Component\Console\Command\Command;
use Symfony\Component\Console\Input\InputInterface;
use Symfony\Component\Console\Input\InputOption;
use Symfony\Component\Console\Output\OutputInterface;
use Symfony\Component\Console\Style\SymfonyStyle;

#[AsCommand(
    name: 'maggie:cookbook:import-ciqual',
    description: 'Import ANSES Ciqual food composition data from XML files',
)]
class ImportCiqualCommand extends Command
{
    private const DEFAULT_DATA_DIR = __DIR__ . '/../../data/ciqual';

    public function __construct(
        private readonly CiqualImporter $importer,
    ) {
        parent::__construct();
    }

    protected function configure(): void
    {
        $this
            ->addOption('data-dir', 'd', InputOption::VALUE_REQUIRED, 'Path to Ciqual XML data directory', self::DEFAULT_DATA_DIR)
            ->addOption('force', 'f', InputOption::VALUE_NONE, 'Truncate existing data before import');
    }

    protected function execute(InputInterface $input, OutputInterface $output): int
    {
        $io = new SymfonyStyle($input, $output);
        $dataDir = $input->getOption('data-dir');
        $force = $input->getOption('force');

        $constFile = $dataDir . '/const.xml';
        $alimFile = $dataDir . '/alim.xml';
        $alimGrpFile = $dataDir . '/alim_grp.xml';
        $compoFile = $dataDir . '/compo.xml';

        foreach ([$constFile, $alimFile, $compoFile] as $file) {
            if (!file_exists($file)) {
                $io->error("File not found: {$file}");

                return Command::FAILURE;
            }
        }

        if (!file_exists($alimGrpFile)) {
            $io->warning("Group names file not found: {$alimGrpFile} — group names will be empty.");
        }

        if ($force) {
            $io->warning('Truncating existing Ciqual data...');
            $this->importer->truncateAll();
        }

        $io->section('Phase 1: Importing nutrients');
        $this->importer->importNutrients($constFile, $io);

        $io->section('Phase 2: Importing foods');
        $this->importer->importFoods($alimFile, $alimGrpFile, $io);

        $io->section('Phase 3: Importing compositions');
        $this->importer->importCompositions($compoFile, $io);

        $io->success('Ciqual import completed.');

        return Command::SUCCESS;
    }
}
