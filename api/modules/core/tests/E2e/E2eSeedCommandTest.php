<?php

declare(strict_types=1);

namespace Maggie\Core\Tests\E2e;

use Doctrine\Common\DataFixtures\Purger\ORMPurger;
use Doctrine\ORM\EntityManagerInterface;
use Fidry\AliceDataFixtures\LoaderInterface;
use Maggie\Calendar\Entity\Agenda;
use Maggie\Calendar\Entity\DayPlan;
use Maggie\Calendar\Entity\Event;
use Maggie\Calendar\Entity\Reward;
use Maggie\Calendar\Entity\Task;
use Maggie\Calendar\Entity\TaskReminder;
use Maggie\Calendar\Enum\DayMoment;
use Maggie\Calendar\Service\AgendaCandidate;
use Maggie\Calendar\Service\AgendaChoiceKind;
use Maggie\Calendar\Service\AgendaSuggester;
use Maggie\Cookbook\Entity\Meal;
use Maggie\Cookbook\Entity\Recipe;
use Maggie\Cookbook\Entity\ShortageAlert;
use Maggie\Core\E2e\Command\E2eSeedCommand;
use Maggie\Core\E2e\Fixture\E2eDateProvider;
use Maggie\Core\Entity\User;
use Maggie\Finance\Entity\Account;
use Maggie\Finance\Entity\Envelope;
use Maggie\Finance\Entity\Placement;
use Maggie\Finance\Entity\ProjectFundingSource;
use Maggie\Finance\Entity\Transaction;
use Maggie\Finance\UseCase\SuggestCategorizationRules;
use Maggie\Grocery\Entity\Errand;
use Maggie\Grocery\Entity\GroceryItem;
use Maggie\Grocery\Entity\GroceryList;
use Maggie\Grocery\Entity\RecurringGroceryItem;
use Maggie\Grocery\Entity\Store;
use Maggie\Grocery\Service\PurchaseSuggestions;
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
        // (MAG-294). The fifth is the approval cards journey's, which answers a
        // held deletion in the chat (MAG-6). The sixth is the savings advances
        // journey's, whose salary day and « Salaire » series no other journey
        // may read (MAG-276). The seventh is the recurring operations detection
        // journey's, whose rent debits would move another account's train de
        // vie (MAG-47). The eighth is the internal transfers journey's, which
        // compares that figure with itself (MAG-272). The ninth is the meals and
        // grocery list journey's: asking Maggie to prepare the week adds every
        // recurring item due, which on the neighbour would pre-empt the errand
        // journey (MAG-300). The tenth and eleventh are the envelope alert
        // journeys', web and mobile (MAG-49): the alert goes out once per
        // period, so neither can share it. The twelfth is the seasonality
        // journey's: it writes to a grocery list and to the chat (MAG-494). The
        // thirteenth is the scores journeys' (MAG-417): a rent series and a
        // month of spending no other journey may move. The fourteenth is the
        // « À venir » journeys' (MAG-463): a next-month rent and an adjusted
        // envelope that no other journey may read. The fifteenth is the month
        // balance's (MAG-457): a held envelope and an overrun one that only
        // Dashboard › État courant reads. The sixteenth is the bravo's (MAG-508):
        // a shooting star comes once a day per user, and the other journeys tick
        // tasks. The seventeenth and eighteenth are the constellation's (MAG-509),
        // web and mobile: four stars this month and seven last month, a count the
        // journey asserts exactly. The nineteenth is the review activity's
        // (MAG-456): two unrecognised spends the review walks and resumes on, and
        // nothing else to review. The twentieth is the period review's (MAG-462):
        // an account and « Loisirs », the quarter's spend is the journey's own. The
        // twenty-first is the reminders' (MAG-510): « Dans 10 min » deletes the
        // notification its journey reads, and the other journeys count the
        // bell's. The twenty-second is the « Prochain projet »'s (MAG-529), the
        // twenty-third the placement withdrawal's (MAG-556): a PEA and a trip that
        // takes capital out of it. The twenty-fourth is the Courses card's (MAG-563): six
        // open lines at one shop. The twenty-fifth and twenty-sixth are the home tasks
        // card's (MAG-561), web and mobile: its ring counts the day's tasks exactly,
        // and the journeys write theirs. The twenty-seventh is the « Il manque »'s (MAG-570):
        // answering it deletes the notification its journey reads. The twenty-eighth is the
        // Accueil's Nutrition card's (MAG-564): a dinner tonight and a lunch tomorrow, which
        // would move what the calendar journeys count on the owner's account. A twenty-ninth
        // would mean somebody added an account without saying why.
        self::assertSame(29, $this->rowsOf(User::class));
        self::assertNotNull($this->repository(User::class)->findOneBy(['email' => 'e2e@maggie.local']));
        self::assertNotNull($this->repository(User::class)->findOneBy(['email' => 'e2e-other@maggie.local']));
        self::assertNotNull($this->repository(User::class)->findOneBy(['email' => 'e2e-interrupt@maggie.local']));
        self::assertNotNull($this->repository(User::class)->findOneBy(['email' => 'e2e-stock@maggie.local']));
        self::assertNotNull($this->repository(User::class)->findOneBy(['email' => 'e2e-approvals@maggie.local']));
        self::assertNotNull($this->repository(User::class)->findOneBy(['email' => 'e2e-treasury@maggie.local']));
        self::assertNotNull($this->repository(User::class)->findOneBy(['email' => 'e2e-recurring@maggie.local']));
        self::assertNotNull($this->repository(User::class)->findOneBy(['email' => 'e2e-transfers@maggie.local']));
        self::assertNotNull($this->repository(User::class)->findOneBy(['email' => 'e2e-meals@maggie.local']));
        self::assertNotNull($this->repository(User::class)->findOneBy(['email' => 'e2e-envelope-alert@maggie.local']));
        self::assertNotNull($this->repository(User::class)->findOneBy(['email' => 'e2e-envelope-alert-mobile@maggie.local']));
        self::assertNotNull($this->repository(User::class)->findOneBy(['email' => 'e2e-season@maggie.local']));
        self::assertNotNull($this->repository(User::class)->findOneBy(['email' => 'e2e-scores@maggie.local']));
        self::assertNotNull($this->repository(User::class)->findOneBy(['email' => 'e2e-adjust@maggie.local']));
        self::assertNotNull($this->repository(User::class)->findOneBy(['email' => 'e2e-balance@maggie.local']));
        self::assertNotNull($this->repository(User::class)->findOneBy(['email' => 'e2e-bravo@maggie.local']));
        self::assertNotNull($this->repository(User::class)->findOneBy(['email' => 'e2e-stars@maggie.local']));
        self::assertNotNull($this->repository(User::class)->findOneBy(['email' => 'e2e-stars-mobile@maggie.local']));
        self::assertNotNull($this->repository(User::class)->findOneBy(['email' => 'e2e-activity@maggie.local']));
        self::assertNotNull($this->repository(User::class)->findOneBy(['email' => 'e2e-period@maggie.local']));
        self::assertNotNull($this->repository(User::class)->findOneBy(['email' => 'e2e-reminders@maggie.local']));
        self::assertNotNull($this->repository(User::class)->findOneBy(['email' => 'e2e-next-project@maggie.local']));
        self::assertNotNull($this->repository(User::class)->findOneBy(['email' => 'e2e-placement-withdrawal@maggie.local']));
        self::assertNotNull($this->repository(User::class)->findOneBy(['email' => 'e2e-home@maggie.local']));
        self::assertNotNull($this->repository(User::class)->findOneBy(['email' => 'e2e-home-tasks@maggie.local']));
        self::assertNotNull($this->repository(User::class)->findOneBy(['email' => 'e2e-home-tasks-mobile@maggie.local']));
        self::assertNotNull($this->repository(User::class)->findOneBy(['email' => 'e2e-shortage@maggie.local']));
        self::assertNotNull($this->repository(User::class)->findOneBy(['email' => 'e2e-nutrition@maggie.local']));

        // Five: the signed-in user's four, and the neighbour's one. An event
        // belongs to a user through its agenda, so the neighbour needs one of
        // their own for the isolation journey to have anything to leak. Four and
        // not two since MAG-150: the agenda deduction has nothing to deduce
        // between two agendas, and « Boulot » and « Concerts » are what the
        // journeys watch an event be filed into. Six since MAG-6: the approval
        // cards journey deletes an event from an agenda of its own. Seven since
        // MAG-570: the « Il manque » account's « Repas ». Eight since MAG-564: the
        // Nutrition card's.
        self::assertSame(8, $this->rowsOf(Agenda::class));
        // Events include the meals, which extend Event: 15 events + 5 meals. Four
        // of the fifteen are the habits the deduction reads (MAG-150), one the
        // day on the 1st of the month (MAG-382).
        self::assertSame(20, $this->rowsOf(Event::class));
        self::assertSame(5, $this->rowsOf(Meal::class));
        // Eight since MAG-405: one task due two days ago, never done. Eleven since
        // MAG-406: the three whose order « Aujourd'hui » is read in. Twelve since
        // MAG-510: the reminders account's « Appeler le garagiste ».
        self::assertSame(12, $this->rowsOf(Task::class));
        self::assertSame(7, $this->rowsOf(Recipe::class));
        // Five on the owner's list, three on the shopper's, six on the Courses card's (MAG-563).
        self::assertSame(14, $this->rowsOf(GroceryItem::class));
        // One list each, and pinned here rather than only per user: a fourth
        // appearing in the fixtures would otherwise go unnoticed.
        self::assertSame(3, $this->rowsOf(GroceryList::class));
        // Three since MAG-495: the owner's coffee, bought every 14 days.
        self::assertSame(3, $this->rowsOf(Errand::class));
        // Two more since MAG-49: each envelope alert account syncs a bank account of its own.
        // Five since MAG-417: the scores account's current account. Six since
        // MAG-463: the « À venir » account's. Seven since MAG-457: the month
        // balance account's. Eight since MAG-456: the review activity's. Nine since MAG-462: the period review's.
        // Ten since MAG-524: the stock user's « Livret jeune », the car's savings source.
        // Thirteen since MAG-529: the next project account's current account, Livret jeune and Livret A.
        // Fourteen since MAG-556: the placement withdrawal account's PEA. Fifteen since MAG-565: the Accueil account's current account.
        self::assertSame(15, $this->rowsOf(Account::class));
        // Thirteen and not twelve since MAG-46: the rent received last month is
        // what the independence counter reads as a rente, and the journey
        // asserts the 60 % it makes of the measured train de vie. Sixteen since
        // MAG-45: three unfiled cheese-shop debits are the one rule suggestion.
        // Eighteen since MAG-49: the 70 % each envelope alert account has spent.
        // Twenty since MAG-417: the scores account's rent and groceries.
        // Twenty-two since MAG-457: the month balance account's groceries and train.
        // Twenty-four since MAG-456: the two spends the review activity walks.
        // Twenty-six since MAG-529: the next project account's restaurants and Obsydian overruns. Twenty-seven since MAG-565: the Accueil account's bakery.
        self::assertSame(27, $this->rowsOf(Transaction::class));
        // Four since MAG-49: each envelope alert account's « Loisirs ». Six since
        // MAG-417: the scores account's « Logement » and « Alimentation ». Nine
        // since MAG-463: the « À venir » account's current and next month's.
        // Eleven since MAG-457: the month balance account's « Alimentation » and « Transport ».
        // Thirteen since MAG-529: the next project account's « Restaurants » and « Obsydian ». Fourteen since MAG-565: the Accueil account's « Alimentation ».
        self::assertSame(14, $this->rowsOf(Envelope::class));
        // Three since MAG-510: the nudge the reminders journey answers. Four since
        // MAG-570: the « Il manque : Parmesan » the shortage journey answers.
        self::assertSame(4, $this->rowsOf(Notification::class));

        // MAG-556: the PEA of the placement withdrawal journey, and the 1 500 € the
        // trip takes out of it the month it is bought.
        $lea = $this->repository(User::class)->findOneBy(['email' => 'e2e-placement-withdrawal@maggie.local']);
        self::assertSame(1, $this->rowsOf(Placement::class, ['user' => $lea]));
        $sources = $this->repository(ProjectFundingSource::class)->findAll();
        self::assertCount(1, $sources);
        self::assertSame(150000, $sources[0]->getAmountCents());
        self::assertSame('PEA', $sources[0]->getPlacement()?->getName());
        self::assertSame('Voyage au Japon', $sources[0]->getProject()->getName());
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
        self::assertSame(10, $this->rowsOf(Task::class, ['user' => $user]));
        self::assertSame(2, $this->rowsOf(Recipe::class, ['user' => $user]));
        self::assertSame(3, $this->rowsOf(Errand::class, ['user' => $user]));
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
        foreach ([Account::class, Transaction::class, Notification::class, Errand::class] as $entity) {
            self::assertSame(0, $this->rowsOf($entity, ['user' => $neighbour]), $entity);
        }
    }

    public function testTheCoursesCardAccountHasSixOpenLinesAtOneShop(): void
    {
        // MAG-563: the card shows five lines and « + 1 » — one more or one fewer and the journey says why.
        $this->seed();

        $home = $this->repository(User::class)->findOneBy(['email' => 'e2e-home@maggie.local']);
        self::assertNotNull($home);
        self::assertSame(1, $this->rowsOf(Store::class, ['user' => $home]));

        $list = $this->repository(GroceryList::class)->findOneBy(['user' => $home]);
        self::assertNotNull($list);
        self::assertSame(6, $this->rowsOf(GroceryItem::class, ['groceryList' => $list, 'checked' => false]));
    }

    public function testTheShortageAccountHasAParmesanMissingForTonightsDinner(): void
    {
        // MAG-570: the alert, its notification and the meal it points at are what the journey answers.
        $this->seed();

        $user = $this->repository(User::class)->findOneBy(['email' => 'e2e-shortage@maggie.local']);
        self::assertNotNull($user);
        $alert = $this->repository(ShortageAlert::class)->findOneBy(['user' => $user]);
        self::assertNotNull($alert);
        self::assertFalse($alert->isResolved());
        self::assertSame('Parmesan', $alert->getProduct()->getName());
        self::assertSame('dinner', $alert->getMeal()->getSlot()->value);

        $notification = $this->repository(Notification::class)->findOneBy(['user' => $user]);
        self::assertNotNull($notification);
        self::assertSame('/api/shortage-alerts/'.$alert->getId(), $notification->getRelatedEntityIri());
        self::assertSame(['add'], $notification->getActions());
    }

    public function testTheFinanceCardAccountSpendsLessThanItsEnvelope(): void
    {
        // MAG-565: the card says « Dans le rythme » — a 1 000 € envelope and 10 € spent, never the other way round.
        $this->seed();

        $home = $this->repository(User::class)->findOneBy(['email' => 'e2e-home@maggie.local']);
        self::assertNotNull($home);
        self::assertSame(1, $this->rowsOf(Envelope::class, ['user' => $home]));
        self::assertSame(1, $this->rowsOf(Transaction::class, ['user' => $home]));
        self::assertSame(100000, $this->repository(Envelope::class)->findOneBy(['user' => $home])?->getAmountCents());
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

    public function testTheOwnerIsToldCoffeeIsDueOnTheSeedDay(): void
    {
        // grocery-list.spec.ts reads « Café · toutes les 2 sem. » in « Souvent pris », and
        // chat.spec.ts asks Maggie what is missing (MAG-495). Coffee alone: a second
        // suggestion would be another row the journey does not expect.
        $this->seed();

        $user = $this->repository(User::class)->findOneBy(['email' => 'e2e@maggie.local']);
        self::assertNotNull($user);

        $suggestions = self::getContainer()->get(PurchaseSuggestions::class);
        $today = $suggestions->today()->format('Y-m-d');
        $due = $suggestions->suggest($user);

        self::assertCount(1, $due);
        self::assertSame('Café', $due[0]['product']['name']);
        self::assertSame(14, $due[0]['everyDays']);
        self::assertSame($today, $due[0]['dueOn']);
    }

    public function testTheRemindersAccountHoldsASentNudgeItsBellCanAnswer(): void
    {
        // notifications-bell.spec.ts presses « Dans 10 min » under the seeded nudge
        // (MAG-510): the notification must name its reminder, the reminder its
        // task, and the confirmed day hold that task first — ids the fixture
        // writes as strings, which a wrong expression would leave as « Ulid ».
        $this->seed();

        $user = $this->repository(User::class)->findOneBy(['email' => 'e2e-reminders@maggie.local']);
        self::assertNotNull($user);
        $task = $this->repository(Task::class)->findOneBy(['user' => $user]);
        self::assertNotNull($task);
        $taskId = (string) $task->getId();

        $reminder = $this->repository(TaskReminder::class)->findOneBy(['user' => $user]);
        self::assertNotNull($reminder);
        self::assertSame(TaskReminder::KIND_NUDGE, $reminder->getKind());
        self::assertSame(DayMoment::AfterBreakfast, $reminder->getMoment());
        self::assertSame($taskId, $reminder->getSubjectId());
        self::assertNotNull($reminder->getSentAt());
        self::assertNull($reminder->getAction());

        $notification = $reminder->getNotification();
        self::assertNotNull($notification);
        self::assertSame((string) $reminder->getId(), $notification->getReminderId());
        self::assertSame(['start', 'in10', 'tonight'], $notification->getActions());
        self::assertSame('/api/tasks/'.$taskId, $notification->getRelatedEntityIri());

        $day = $this->repository(DayPlan::class)->findOneBy(['user' => $user]);
        self::assertNotNull($day);
        self::assertNotNull($day->getConfirmedAt());
        self::assertSame([$taskId], $day->getTaskIds());
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

    public function testTheConstellationAccountsStartWithFourStarsThisMonthAndSevenLastMonth(): void
    {
        // The constellation journeys (MAG-509) assert « 5 étoiles » after one
        // tick and « 7 étoiles » on the month before: both counts are the seed's.
        $this->seed(['--now' => '2026-03-15T12:00:00+01:00']);

        foreach (['e2e-stars@maggie.local', 'e2e-stars-mobile@maggie.local'] as $email) {
            $user = $this->repository(User::class)->findOneBy(['email' => $email]);
            self::assertNotNull($user);

            $perMonth = [];
            foreach ($this->repository(Reward::class)->findBy(['user' => $user]) as $reward) {
                $month = $reward->getEarnedAt()->setTimezone(new \DateTimeZone('Europe/Paris'))->format('Y-m');
                $perMonth[$month] = ($perMonth[$month] ?? 0) + 1;
            }

            self::assertSame(['2026-02' => 7, '2026-03' => 4], $perMonth, $email);
        }
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
        self::assertSame(30, $this->rowsOf(User::class));

        $this->seed();

        self::assertSame(29, $this->rowsOf(User::class), 'The seed must clear what a previous suite left behind.');
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

    public function testTheAllDayEventOfTheFirstEndsOnTheSecondExcluded(): void
    {
        // PHP applies `first day of` after every other relative part of a
        // modifier: "first day of this month +1 day" is still the 1st, and the
        // event ended where it started (MAG-382).
        $this->seed(['--now' => '2026-03-15T12:00:00+01:00']);

        $first = $this->repository(Event::class)->findOneBy(['summary' => 'Journée du 1er MAG-382']);
        self::assertNotNull($first);
        self::assertSame('2026-03-01', $first->getStartDate()?->format('Y-m-d'));
        self::assertSame('2026-03-02', $first->getEndDate()?->format('Y-m-d'));
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
            // What « Souvent pris » suggests (MAG-495).
            'e2e_product_coffee',
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
            'errands' => $this->rowsOf(Errand::class),
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
            new \Maggie\Core\E2e\Fixture\E2eFixtureCatalog($container->getParameter('kernel.project_dir')),
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
