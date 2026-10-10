<?php

declare(strict_types=1);

namespace Maggie\Core\Tests\E2e;

use Doctrine\Common\DataFixtures\Purger\ORMPurger;
use Doctrine\ORM\EntityManagerInterface;
use Fidry\AliceDataFixtures\LoaderInterface;
use Maggie\Calendar\Entity\Agenda;
use Maggie\Calendar\Entity\Event;
use Maggie\Calendar\Entity\Task;
use Maggie\Calendar\Service\AgendaCandidate;
use Maggie\Calendar\Service\AgendaChoiceKind;
use Maggie\Calendar\Service\AgendaSuggester;
use Maggie\Cookbook\Entity\Meal;
use Maggie\Cookbook\Entity\Recipe;
use Maggie\Core\E2e\Command\E2eSeedCommand;
use Maggie\Core\E2e\Fixture\E2eDateProvider;
use Maggie\Core\Entity\User;
use Maggie\Finance\Entity\Account;
use Maggie\Finance\Entity\Envelope;
use Maggie\Finance\Entity\Transaction;
use Maggie\Finance\UseCase\SuggestCategorizationRules;
use Maggie\Grocery\Entity\GroceryItem;
use Maggie\Grocery\Entity\GroceryList;
use Maggie\Grocery\Entity\RecurringGroceryItem;
use Maggie\Grocery\Entity\Store;
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

        // Four users, and only four. Journeys sign in as e2e@maggie.local; the
        // second account exists so the web harness can prove a Mercure update
        // published for one user never reaches the other (MAG-97), and is also
        // the shopper of the grocery journeys, which need a list nobody else
        // writes to (MAG-101 — see 10-core.yaml). The third is the only one
        // Maggie's interruptions are published to (MAG-311). The fourth is the
        // stock journey's, which writes to a list and a chat history of its own
        // (MAG-294). A fifth would mean somebody added an account without saying
        // why.
        self::assertSame(4, $this->rowsOf(User::class));
        self::assertNotNull($this->repository(User::class)->findOneBy(['email' => 'e2e@maggie.local']));
        self::assertNotNull($this->repository(User::class)->findOneBy(['email' => 'e2e-other@maggie.local']));
        self::assertNotNull($this->repository(User::class)->findOneBy(['email' => 'e2e-interrupt@maggie.local']));
        self::assertNotNull($this->repository(User::class)->findOneBy(['email' => 'e2e-stock@maggie.local']));

        // Five: the signed-in user's four, and the neighbour's one. An event
        // belongs to a user through its agenda, so the neighbour needs one of
        // their own for the isolation journey to have anything to leak. Four and
        // not two since MAG-150: the agenda deduction has nothing to deduce
        // between two agendas, and « Boulot » and « Concerts » are what the
        // journeys watch an event be filed into.
        self::assertSame(5, $this->rowsOf(Agenda::class));
        // Events include the meals, which extend Event: 15 events + 2 meals. Four
        // of the fifteen are the habits the deduction reads (MAG-150), one the
        // day on the 1st of the month (MAG-382).
        self::assertSame(17, $this->rowsOf(Event::class));
        self::assertSame(2, $this->rowsOf(Meal::class));
        self::assertSame(4, $this->rowsOf(Task::class));
        self::assertSame(3, $this->rowsOf(Recipe::class));
        // Five on the owner's list, three on the shopper's.
        self::assertSame(8, $this->rowsOf(GroceryItem::class));
        // One list each, and pinned here rather than only per user: a third
        // appearing in the fixtures would otherwise go unnoticed.
        self::assertSame(2, $this->rowsOf(GroceryList::class));
        self::assertSame(2, $this->rowsOf(Account::class));
        // Thirteen and not twelve since MAG-46: the rent received last month is
        // what the independence counter reads as a rente, and the journey
        // asserts the 60 % it makes of the measured train de vie. Sixteen since
        // MAG-45: three unfiled cheese-shop debits are the one rule suggestion.
        self::assertSame(16, $this->rowsOf(Transaction::class));
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
        self::assertSame(4, $this->rowsOf(Agenda::class, ['user' => $user]));
        self::assertSame(3, $this->rowsOf(Task::class, ['user' => $user]));
        self::assertSame(2, $this->rowsOf(Recipe::class, ['user' => $user]));
        self::assertSame(2, $this->rowsOf(Account::class, ['user' => $user]));
        self::assertSame(16, $this->rowsOf(Transaction::class, ['user' => $user]));
        self::assertSame(2, $this->rowsOf(Notification::class, ['user' => $user]));
    }

    public function testTheNeighbourOwnsOnlyWhatTheIsolationJourneyNeeds(): void
    {
        $this->seed();

        $neighbour = $this->repository(User::class)->findOneBy(['email' => 'e2e-other@maggie.local']);
        self::assertNotNull($neighbour);

        // One agenda, one event in it and one task: exactly what MAG-100's
        // isolation journey asks the signed-in user's dashboard and agenda to
        // come back *without*. Proving "nothing of theirs leaks" needs something
        // of theirs to leak — an empty neighbour makes the assertion hold for
        // the wrong reason.
        self::assertSame(1, $this->rowsOf(Agenda::class, ['user' => $neighbour]));
        self::assertSame(1, $this->rowsOf(Task::class, ['user' => $neighbour]));

        // One recipe, and a meal in that same agenda planning it (MAG-101).
        // The recipe shares a tag with one of Camille's, so a tag search
        // missing its user filter returns both; the meal sits in the week her
        // list is generated over, so a date-range read missing its own filter
        // puts the neighbour's leek on her shopping (MAG-114 § 3).
        self::assertSame(1, $this->rowsOf(Recipe::class, ['user' => $neighbour]));

        $agenda = $this->repository(Agenda::class)->findOneBy(['user' => $neighbour]);
        self::assertNotNull($agenda);
        self::assertSame(2, $this->rowsOf(Event::class, ['agenda' => $agenda]));
        self::assertSame(1, $this->rowsOf(Meal::class, ['agenda' => $agenda]));

        // And a shop to do (MAG-101). This account has a second job — it is the
        // shopper of `grocery-errand.spec.ts`, the one journey that has to own a
        // grocery list outright, because ending an errand deletes every ticked
        // line and because a payload published for somebody else's write would
        // satisfy "this write published". 10-core.yaml spells the reasoning out.
        self::assertSame(2, $this->rowsOf(Store::class, ['user' => $neighbour]));
        self::assertSame(1, $this->rowsOf(RecurringGroceryItem::class, ['user' => $neighbour]));

        $list = $this->repository(GroceryList::class)->findOneBy(['user' => $neighbour]);
        self::assertNotNull($list, 'the shopper has no grocery list');
        self::assertSame(3, $this->rowsOf(GroceryItem::class, ['groceryList' => $list]));

        // The modules no journey reaches as this account stay empty: data
        // nobody reads would only make the counts above harder to keep.
        foreach ([Account::class, Transaction::class, Notification::class] as $entity) {
            self::assertSame(0, $this->rowsOf($entity, ['user' => $neighbour]), $entity);
        }
    }

    public function testTheAgendaDeductionReadsTheSeedTheWayTheJourneysExpect(): void
    {
        // The three MAG-150 journeys in `e2e/web/tests/chat.spec.ts` assert which agenda
        // an event Maggie creates lands in, and the answer comes out of the whole seeded
        // world: every agenda, and every plain event starting before `now + 30 days`
        // (meals are skipped, `AgendaSuggester::scoreHistory`). So a fixture added
        // anywhere can change it, and the browser suite is the slowest and least legible
        // place to find out. Here it fails in two seconds, naming the agenda it got.
        $this->seed();

        $user = $this->repository(User::class)->findOneBy(['email' => 'e2e@maggie.local']);
        self::assertNotNull($user);
        $suggester = self::getContainer()->get(AgendaSuggester::class);

        // « an event nobody placed lands in the agenda its own words point at »
        $concert = $suggester->suggest($user, 'Concert de Stromae');
        self::assertSame(AgendaChoiceKind::Deduced, $concert->kind);
        self::assertSame('Concerts', $concert->agenda?->getName());

        // « an appointment nobody placed lands where the past ones went »
        $paul = $suggester->suggest($user, 'Rendez-vous avec Paul');
        self::assertSame(AgendaChoiceKind::Deduced, $paul->kind);
        self::assertSame('Boulot', $paul->agenda?->getName());

        // « an event two agendas fit is not created until the owner says which one » —
        // and those two agendas, in that order, are what the refusal names.
        $camille = $suggester->suggest($user, 'Déjeuner avec Camille');
        self::assertSame(AgendaChoiceKind::Ambiguous, $camille->kind);
        self::assertSame(
            ['Boulot', 'Famille'],
            array_map(static fn (AgendaCandidate $c) => $c->agenda->getName(), $camille->candidates),
        );

        // And the three writes the older chat journeys make still go through without a
        // question: 35-create-event.yaml, 36-create-event-retry.yaml and
        // 38-create-event-timezone.yaml all call `create_event` with no agenda at all,
        // and a doubt there would fail them as « the tool errored » with nothing
        // pointing at the fixture that caused it.
        $dentist = $suggester->suggest($user, 'Dentiste');
        self::assertSame(AgendaChoiceKind::Deduced, $dentist->kind);
        self::assertSame('Perso', $dentist->agenda?->getName());

        $wizards = $suggester->suggest($user, 'Black Wizards');
        self::assertSame(AgendaChoiceKind::Fallback, $wizards->kind);
        self::assertSame('Perso', $wizards->agenda?->getName());

        $kevin = $suggester->suggest($user, 'Appeler Kévin');
        self::assertNotSame(AgendaChoiceKind::Ambiguous, $kevin->kind);
        self::assertSame('Perso', $kevin->agenda?->getName());
    }

    public function testTheSeedImpliesExactlyTheRuleTheJourneysAccept(): void
    {
        // finance-rules.spec.ts and 09-finance-banks.yaml both accept one suggestion
        // and then expect none left: a second one, or a heading already chosen,
        // fails them on CI far from the fixture that caused it.
        $this->seed();

        $user = $this->repository(User::class)->findOneBy(['email' => 'e2e@maggie.local']);
        self::assertNotNull($user);

        $suggestions = self::getContainer()->get(SuggestCategorizationRules::class)->suggest($user);

        self::assertCount(1, $suggestions);
        self::assertSame('FROMAGERIE DES LICES', $suggestions[0]['pattern']);
        self::assertSame(3, $suggestions[0]['occurrences']);
        self::assertSame(-6700, $suggestions[0]['totalCents']);
        self::assertNull($suggestions[0]['categoryId']);
    }

    public function testTheSeedStoresHistoryAsWrittenWithoutFilingIt(): void
    {
        // finance-rules.spec.ts starts from an operation the seeded rule is
        // waiting for: recording it at load would file it before the journey.
        $this->seed();

        $leclerc = $this->repository(Transaction::class)->findOneBy(['label' => 'LECLERC DRIVE RENNES']);

        self::assertNotNull($leclerc);
        self::assertNull($leclerc->getCategory());
        self::assertSame(\Maggie\Finance\Enum\CategorySource::None, $leclerc->getCategorySource());
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
        self::assertSame(5, $this->rowsOf(User::class));

        $this->seed();

        self::assertSame(4, $this->rowsOf(User::class), 'The seed must clear what a previous suite left behind.');
    }

    public function testDatesFollowTheAnchor(): void
    {
        $this->seed(['--now' => '2026-03-15T12:00:00+01:00']);

        $lunch = $this->repository(Event::class)->findOneBy(['summary' => 'Déjeuner avec Alex']);
        self::assertNotNull($lunch);
        self::assertSame('2026-03-15 12:00', $lunch->getStartAt()->setTimezone(new \DateTimeZone('Europe/Paris'))->format('Y-m-d H:i'));

        // The envelope is keyed by the anchor's year and month, not the wall
        // clock — the case that breaks silently on the first of a month.
        $envelope = $this->repository(Envelope::class)->findOneBy(['month' => 3]);
        self::assertNotNull($envelope);
        self::assertSame(2026, $envelope->getYear());
    }

    public function testTheDayOfTheSeedIsTheDayInParisNotInUtc(): void
    {
        // 23:30 UTC on 14 July is 01:30 on the 15th in Paris. The user of the
        // test stack is in Paris, and the dashboard shows the lunch of the 15th:
        // a seed anchored on UTC midnight put it on the 14th, and the dashboard
        // was empty between midnight and 2 am.
        $this->seed(['--now' => '2026-07-14T23:30:00+00:00']);

        $lunch = $this->repository(Event::class)->findOneBy(['summary' => 'Déjeuner avec Alex']);
        self::assertNotNull($lunch);
        self::assertSame('2026-07-15 12:00', $lunch->getStartAt()->setTimezone(new \DateTimeZone('Europe/Paris'))->format('Y-m-d H:i'));
        self::assertSame('2026-07-15 13:00', $lunch->getEndAt()->setTimezone(new \DateTimeZone('Europe/Paris'))->format('Y-m-d H:i'));

        // Dates without a time of day follow the same day.
        $firstOfMonth = $this->repository(Transaction::class)->findOneBy(['bookedAt' => new \DateTimeImmutable('2026-07-01')]);
        self::assertNotNull($firstOfMonth);

        self::assertSame('2026-07-15T00:00:00+02:00', $this->readManifest()['anchor']);
    }

    public function testAnchorIsRecordedInTheManifest(): void
    {
        $this->seed(['--now' => '2026-03-15T12:00:00+01:00']);

        $manifest = $this->readManifest();

        self::assertSame('2026-03-15T00:00:00+01:00', $manifest['anchor']);
    }

    public function testAnOffsetlessNowIsReadInParis(): void
    {
        $this->seed(['--now' => '2026-07-15']);

        self::assertSame('2026-07-15T00:00:00+02:00', $this->readManifest()['anchor']);
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

        $read = [
            'e2e_other_user', 'e2e_agenda_personal', 'e2e_recipe_pasta', 'e2e_account_checking', 'e2e_grocery_list',
            // What the recipes, menus and groceries journeys address (MAG-101).
            'e2e_store_supermarket', 'e2e_store_greengrocer', 'e2e_ingredient_tomato', 'e2e_grocery_item_deferred',
            'e2e_recurring_milk', 'e2e_other_recipe_soup', 'e2e_other_ingredient_leek',
            'e2e_other_grocery_list', 'e2e_other_store_market', 'e2e_other_store_corner', 'e2e_other_item_leek',
        ];

        foreach ($read as $reference) {
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
            $container->get(\Maggie\Finance\Doctrine\TransactionListener::class),
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
