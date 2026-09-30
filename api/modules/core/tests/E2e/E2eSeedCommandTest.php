<?php

declare(strict_types=1);

namespace Maggie\Core\Tests\E2e;

use Doctrine\Common\DataFixtures\Purger\ORMPurger;
use Doctrine\ORM\EntityManagerInterface;
use Fidry\AliceDataFixtures\LoaderInterface;
use Maggie\Calendar\Entity\Agenda;
use Maggie\Calendar\Entity\Event;
use Maggie\Calendar\Entity\Task;
use Maggie\Cookbook\Entity\Meal;
use Maggie\Cookbook\Entity\Recipe;
use Maggie\Core\E2e\Command\E2eSeedCommand;
use Maggie\Core\E2e\Fixture\E2eDateProvider;
use Maggie\Core\Entity\User;
use Maggie\Finance\Entity\Account;
use Maggie\Finance\Entity\Envelope;
use Maggie\Finance\Entity\Transaction;
use Maggie\Grocery\Entity\GroceryItem;
use Maggie\Notification\Entity\Notification;
use Symfony\Bundle\FrameworkBundle\Test\KernelTestCase;
use Symfony\Component\Console\Application;
use Symfony\Component\Console\Command\Command;
use Symfony\Component\Console\Tester\CommandTester;

/**
 * Runs the real seed against the real fixture files.
 *
 * The command itself only exists in the `e2e` environment, so it is built here
 * by hand from the test container. That is the point of the test: the fixture
 * files are the part most likely to rot — a renamed property or a moved enum
 * breaks them, and without this the breakage would only surface when a journey
 * fails on CI with an error nowhere near the change that caused it.
 *
 * Elasticsearch is skipped throughout (`--skip-search`): the default test suite
 * has no cluster, and the index rebuild is covered by the smoke journey.
 */
final class E2eSeedCommandTest extends KernelTestCase
{
    private CommandTester $tester;

    protected function setUp(): void
    {
        self::bootKernel();

        $application = new Application();
        $application->add($this->command());

        $this->tester = new CommandTester($application->find('app:e2e:seed'));
    }

    protected function tearDown(): void
    {
        // This class leaves the whole e2e world in the shared test
        // database. Any later class that does not purge first would silently
        // depend on the order tests happen to run in.
        (new ORMPurger($this->entityManager()))->purge();
        $this->entityManager()->clear();

        parent::tearDown();
    }

    public function testItLoadsTheWholeFixtureSet(): void
    {
        $this->seed();

        $this->tester->assertCommandIsSuccessful();

        // Two users, and only two. Journeys sign in as e2e@maggie.local; the
        // neighbour exists so the web harness can prove a Mercure update
        // published for one user never reaches the other (MAG-97). A third
        // would mean somebody added an account without saying why.
        self::assertSame(2, $this->rowsOf(User::class));
        self::assertNotNull($this->repository(User::class)->findOneBy(['email' => 'e2e@maggie.local']));
        self::assertNotNull($this->repository(User::class)->findOneBy(['email' => 'e2e-other@maggie.local']));

        self::assertSame(2, $this->rowsOf(Agenda::class));
        // Events include the meal, which extends Event: 5 events + 1 meal.
        self::assertSame(6, $this->rowsOf(Event::class));
        self::assertSame(1, $this->rowsOf(Meal::class));
        self::assertSame(2, $this->rowsOf(Task::class));
        self::assertSame(2, $this->rowsOf(Recipe::class));
        self::assertSame(5, $this->rowsOf(GroceryItem::class));
        self::assertSame(2, $this->rowsOf(Account::class));
        self::assertSame(12, $this->rowsOf(Transaction::class));
        self::assertSame(2, $this->rowsOf(Envelope::class));
        self::assertSame(2, $this->rowsOf(Notification::class));
    }

    public function testEveryRowBelongsToTheSeededUser(): void
    {
        $this->seed();

        $user = $this->repository(User::class)->findOneBy(['email' => 'e2e@maggie.local']);
        self::assertNotNull($user);

        // MCP tools and API collections filter by user. A fixture attached to
        // nobody would simply be invisible, and the journey would report an
        // empty list without saying why.
        self::assertSame(2, $this->rowsOf(Agenda::class, ['user' => $user]));
        self::assertSame(2, $this->rowsOf(Task::class, ['user' => $user]));
        self::assertSame(2, $this->rowsOf(Recipe::class, ['user' => $user]));
        self::assertSame(2, $this->rowsOf(Account::class, ['user' => $user]));
        self::assertSame(12, $this->rowsOf(Transaction::class, ['user' => $user]));
        self::assertSame(2, $this->rowsOf(Notification::class, ['user' => $user]));
    }

    public function testTheNeighbourOwnsNothingButItsPreferences(): void
    {
        $this->seed();

        $neighbour = $this->repository(User::class)->findOneBy(['email' => 'e2e-other@maggie.local']);
        self::assertNotNull($neighbour);

        // The account exists to prove isolation, not to be a second world. Data
        // attached to it would show up in no journey and quietly make the
        // "nothing leaks" assertions weaker than they look.
        foreach ([Agenda::class, Task::class, Recipe::class, Account::class, Transaction::class, Notification::class] as $entity) {
            self::assertSame(0, $this->rowsOf($entity, ['user' => $neighbour]), $entity);
        }
    }

    public function testRunningTwiceLeavesTheSameCounts(): void
    {
        $this->seed();
        $firstRun = $this->snapshot();

        $this->seed();

        self::assertSame($firstRun, $this->snapshot(), 'A second seed must replace the first, not add to it.');
    }

    public function testItPurgesRowsCreatedBetweenTwoRuns(): void
    {
        $this->seed();

        $stray = new User();
        $stray->setEmail('stray@maggie.local');
        $stray->setGoogleId('stray-google-id');
        $stray->setName('Stray');
        $this->entityManager()->persist($stray);
        $this->entityManager()->flush();
        self::assertSame(3, $this->rowsOf(User::class));

        $this->seed();

        self::assertSame(2, $this->rowsOf(User::class), 'The seed must clear what a previous suite left behind.');
    }

    public function testDatesFollowTheAnchor(): void
    {
        $this->seed(['--now' => '2026-03-15T00:00:00+00:00']);

        $lunch = $this->repository(Event::class)->findOneBy(['summary' => 'Déjeuner avec Alex']);
        self::assertNotNull($lunch);
        self::assertSame('2026-03-15 12:00', $lunch->getStartAt()->setTimezone(new \DateTimeZone('UTC'))->format('Y-m-d H:i'));

        // The envelope is keyed by the anchor's year and month, not the wall
        // clock — the case that breaks silently on the first of a month.
        $envelope = $this->repository(Envelope::class)->findOneBy(['month' => 3]);
        self::assertNotNull($envelope);
        self::assertSame(2026, $envelope->getYear());
    }

    public function testAnchorIsRecordedInTheManifest(): void
    {
        $this->seed(['--now' => '2026-03-15T00:00:00+00:00']);

        $manifest = $this->readManifest();

        self::assertSame('2026-03-15T00:00:00+00:00', $manifest['anchor']);
    }

    public function testManifestMapsEveryReferenceToAnId(): void
    {
        $this->seed();

        $manifest = $this->readManifest();

        // Journeys address rows through this map, because ULIDs carry a
        // timestamp and differ between runs even with identical data.
        self::assertArrayHasKey('e2e_user', $manifest['references']);
        self::assertSame(User::class, $manifest['references']['e2e_user']['class']);
        self::assertMatchesRegularExpression('/^[0-9A-HJKMNP-TV-Z]{26}$/', $manifest['references']['e2e_user']['id']);

        foreach (['e2e_other_user', 'e2e_agenda_personal', 'e2e_recipe_pasta', 'e2e_account_checking', 'e2e_grocery_list'] as $reference) {
            self::assertArrayHasKey($reference, $manifest['references']);
        }
    }

    public function testAnUnparsableNowIsRejected(): void
    {
        $exitCode = $this->tester->execute(['--now' => 'not-a-date', '--skip-search' => true]);

        self::assertSame(Command::INVALID, $exitCode);
        self::assertStringContainsString('not a date --now understands', $this->tester->getDisplay());
    }

    /** @param array<string, mixed> $options */
    private function seed(array $options = []): void
    {
        $this->tester->execute(array_merge(
            ['--skip-search' => true, '--manifest' => $this->manifestPath()],
            $options,
        ));
        $this->tester->assertCommandIsSuccessful();
        $this->entityManager()->clear();
    }

    /** @return array<string, int> */
    private function snapshot(): array
    {
        return [
            'users' => $this->rowsOf(User::class),
            'agendas' => $this->rowsOf(Agenda::class),
            'events' => $this->rowsOf(Event::class),
            'tasks' => $this->rowsOf(Task::class),
            'recipes' => $this->rowsOf(Recipe::class),
            'groceryItems' => $this->rowsOf(GroceryItem::class),
            'accounts' => $this->rowsOf(Account::class),
            'transactions' => $this->rowsOf(Transaction::class),
            'envelopes' => $this->rowsOf(Envelope::class),
            'notifications' => $this->rowsOf(Notification::class),
        ];
    }

    /** @return array{anchor: string, references: array<string, array{class: string, id: string}>} */
    private function readManifest(): array
    {
        $contents = file_get_contents($this->manifestPath());
        self::assertIsString($contents);

        return json_decode($contents, true, 512, JSON_THROW_ON_ERROR);
    }

    private function manifestPath(): string
    {
        return self::getContainer()->getParameter('kernel.project_dir').'/var/test/e2e-seed-manifest.json';
    }

    private function command(): E2eSeedCommand
    {
        $container = self::getContainer();

        $loader = $container->get('fidry_alice_data_fixtures.loader.doctrine');
        self::assertInstanceOf(LoaderInterface::class, $loader);

        return new E2eSeedCommand(
            $this->entityManager(),
            $container->get(E2eDateProvider::class),
            $loader,
            $container->get(\Elastic\Elasticsearch\Client::class),
            $container->getParameter('kernel.project_dir'),
        );
    }

    /** @param class-string $entity @param array<string, mixed> $criteria */
    private function rowsOf(string $entity, array $criteria = []): int
    {
        return $this->repository($entity)->count($criteria);
    }

    /** @param class-string $entity */
    private function repository(string $entity): \Doctrine\ORM\EntityRepository
    {
        return $this->entityManager()->getRepository($entity);
    }

    private function entityManager(): EntityManagerInterface
    {
        return self::getContainer()->get('doctrine.orm.entity_manager');
    }
}
