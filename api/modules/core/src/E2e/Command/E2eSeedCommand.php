<?php

declare(strict_types=1);

namespace Maggie\Core\E2e\Command;

use Doctrine\DBAL\Connection;
use Doctrine\ORM\EntityManagerInterface;
use Elastic\Elasticsearch\Client as ElasticsearchClient;
use Fidry\AliceDataFixtures\LoaderInterface;
use Fidry\AliceDataFixtures\Persistence\PurgeMode;
use Maggie\Core\E2e\Fixture\E2eDateProvider;
use Symfony\Component\Console\Attribute\AsCommand;
use Symfony\Component\Console\Command\Command;
use Symfony\Component\Console\Input\ArrayInput;
use Symfony\Component\Console\Input\InputInterface;
use Symfony\Component\Console\Input\InputOption;
use Symfony\Component\Console\Output\NullOutput;
use Symfony\Component\Console\Output\OutputInterface;
use Symfony\Component\Console\Style\SymfonyStyle;
use Symfony\Component\DependencyInjection\Attribute\Autowire;

/**
 * Puts the e2e database into the one state every journey starts from.
 *
 * Registered only in the `e2e` environment (see modules/core/config/services.yaml),
 * because it truncates every table it can reach.
 *
 * Truncation rather than a schema rebuild is deliberate: it keeps the schema
 * the migrations produced, so a missing migration fails the e2e run instead of
 * being papered over by a `schema:create`.
 */
#[AsCommand(
    name: 'app:e2e:seed',
    description: 'Reset the e2e database to its deterministic fixture set',
)]
final class E2eSeedCommand extends Command
{
    /** Migrations must survive the truncation, or the schema loses its history. */
    private const PRESERVED_TABLES = ['doctrine_migration_versions'];

    public function __construct(
        private readonly EntityManagerInterface $entityManager,
        private readonly E2eDateProvider $dateProvider,
        #[Autowire(service: 'fidry_alice_data_fixtures.loader.doctrine')]
        private readonly LoaderInterface $fixtureLoader,
        private readonly ElasticsearchClient $elasticsearch,
        private readonly string $projectDir,
    ) {
        parent::__construct();
    }

    protected function configure(): void
    {
        $this
            ->addOption(
                'now',
                null,
                InputOption::VALUE_REQUIRED,
                'Anchor every fixture date to this instant (ISO-8601). Defaults to today at midnight UTC.',
            )
            // No --no-purge option on purpose: with a single fixture set,
            // loading it on top of itself violates the first unique index it
            // meets. An option whose only use is broken is worse than no
            // option. Reintroduce it the day a journey ships a second set.
            ->addOption(
                'skip-search',
                null,
                InputOption::VALUE_NONE,
                'Skip the Elasticsearch rebuild. Only for a stack started without the search service.',
            )
            ->addOption(
                'manifest',
                null,
                InputOption::VALUE_REQUIRED,
                'Where to write the reference → id map journeys read.',
                'var/e2e/seed-manifest.json',
            );
    }

    protected function execute(InputInterface $input, OutputInterface $output): int
    {
        $io = new SymfonyStyle($input, $output);

        try {
            $anchor = $this->resolveAnchor($input->getOption('now'));
        } catch (\Exception $e) {
            $io->error($e->getMessage());

            return Command::INVALID;
        }

        $this->dateProvider->setAnchor($anchor);
        $io->text(sprintf('Anchor: <info>%s</info>', $anchor->format(\DATE_ATOM)));

        $truncated = $this->truncateEverything();
        $io->text(sprintf('Truncated <info>%d</info> tables.', $truncated));

        $objects = $this->fixtureLoader->load(
            $this->fixtureFiles(),
            [],
            [],
            PurgeMode::createNoPurgeMode(),
        );

        $io->text(sprintf('Loaded <info>%d</info> objects.', \count($objects)));

        $manifestPath = $this->writeManifest($objects, $anchor, (string) $input->getOption('manifest'));
        $io->text(sprintf('Manifest: <info>%s</info>', $manifestPath));

        if ($input->getOption('skip-search')) {
            $io->warning('Elasticsearch rebuild skipped — search assertions will read a stale index.');
        } else {
            $this->rebuildSearchIndices($io);
        }

        $io->success('E2E fixtures loaded.');

        return Command::SUCCESS;
    }

    private function resolveAnchor(?string $now): \DateTimeImmutable
    {
        if ($now === null || $now === '') {
            return new \DateTimeImmutable('today midnight', new \DateTimeZone('UTC'));
        }

        try {
            return new \DateTimeImmutable($now, new \DateTimeZone('UTC'));
        } catch (\Exception) {
            throw new \InvalidArgumentException(sprintf('"%s" is not a date --now understands.', $now));
        }
    }

    /**
     * CASCADE also empties the join tables, which have no entity metadata of
     * their own and would otherwise keep rows pointing at nothing.
     */
    private function truncateEverything(): int
    {
        $connection = $this->entityManager->getConnection();
        $tables = $this->truncatableTables($connection);

        if ($tables === []) {
            return 0;
        }

        $platform = $connection->getDatabasePlatform();
        // listTableNames() already quotes reserved words — `user` comes back as
        // `"user"` — so strip before quoting, or the statement asks for
        // `""user""` and Postgres reports a table that does not exist.
        $quoted = array_map(
            static fn (string $table): string => $platform->quoteSingleIdentifier(trim($table, '"')),
            $tables,
        );

        $connection->executeStatement(sprintf(
            'TRUNCATE TABLE %s RESTART IDENTITY CASCADE',
            implode(', ', $quoted),
        ));

        $this->entityManager->clear();

        return \count($tables);
    }

    /**
     * Everything the database actually holds, minus what must survive.
     *
     * Read from the schema rather than from entity metadata on purpose: the
     * metadata misses join tables, carries its own quoting (`"user"` is stored
     * quoted, and quoting it again yields `""user""`), and can name a table no
     * migration has created — any of which aborts the whole statement.
     *
     * @return list<string>
     */
    private function truncatableTables(Connection $connection): array
    {
        $tables = array_map(
            static fn (string $table): string => trim($table, '"'),
            $connection->createSchemaManager()->listTableNames(),
        );

        return array_values(array_diff($tables, self::PRESERVED_TABLES));
    }

    /** @return list<string> */
    private function fixtureFiles(): array
    {
        $dir = $this->projectDir . '/fixtures/e2e';
        $files = glob($dir . '/*.yaml');

        if ($files === false || $files === []) {
            throw new \RuntimeException(sprintf('No e2e fixture file found in %s.', $dir));
        }

        // Alphabetical, so the load order is the file names: 10-core.yaml
        // before 20-calendar.yaml, because a calendar belongs to a user.
        sort($files);

        return $files;
    }

    /**
     * @param array<string, object> $objects
     *
     * @return string the path written
     */
    private function writeManifest(array $objects, \DateTimeImmutable $anchor, string $path): string
    {
        $absolute = str_starts_with($path, '/') ? $path : $this->projectDir . '/' . $path;

        $entries = [];
        foreach ($objects as $reference => $object) {
            if (!method_exists($object, 'getId')) {
                continue;
            }

            $entries[$reference] = [
                'class' => $object::class,
                'id' => (string) $object->getId(),
            ];
        }
        ksort($entries);

        $directory = \dirname($absolute);
        if (!is_dir($directory) && !mkdir($directory, 0o775, true) && !is_dir($directory)) {
            throw new \RuntimeException(sprintf('Cannot create %s.', $directory));
        }

        file_put_contents($absolute, json_encode([
            // ULIDs carry a timestamp, so they differ between two runs even
            // with identical data. Journeys address rows through this map
            // rather than through hard-coded ids — which would mean opening a
            // setId() on every entity purely for the tests.
            'anchor' => $anchor->format(\DATE_ATOM),
            'references' => $entries,
        ], JSON_PRETTY_PRINT | JSON_UNESCAPED_SLASHES | JSON_THROW_ON_ERROR) . "\n");

        return $absolute;
    }

    private function rebuildSearchIndices(SymfonyStyle $io): void
    {
        $application = $this->getApplication();

        if ($application === null) {
            throw new \LogicException('The seed command needs a console application to rebuild indices.');
        }

        // A stale index returns an empty list in silence, so a seed that left
        // the old documents behind would make search journeys fail for a
        // reason nowhere near the change that broke them.
        $exitCode = $application->find('app:elasticsearch:reindex')->run(
            new ArrayInput(['--all' => true, '--recreate' => true]),
            new NullOutput(),
        );

        if ($exitCode !== Command::SUCCESS) {
            throw new \RuntimeException('Elasticsearch reindex failed; use --skip-search if the stack has no search service.');
        }

        // Indexing is not the same as being findable: Elasticsearch refreshes
        // on its own schedule, about once a second. Without this, the first
        // read after a seed returns an empty collection — and an empty
        // collection is how a stale index looks, so the journey would fail
        // with a message pointing at the wrong thing.
        $this->elasticsearch->indices()->refresh(['index' => '_all']);

        $io->text('Elasticsearch indices recreated and refreshed.');
    }
}
