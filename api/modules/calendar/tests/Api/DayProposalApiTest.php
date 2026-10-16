<?php

declare(strict_types=1);

namespace Maggie\Calendar\Tests\Api;

use App\Tests\Support\AuthenticatedTestTrait;
use App\Tests\Support\ElasticsearchAssertionTrait;
use App\Tests\Support\FixtureLoaderTrait;
use App\Tests\Support\MercureAssertionTrait;
use Doctrine\ORM\EntityManagerInterface;
use Maggie\Calendar\Entity\DayPlan;
use Maggie\Calendar\Entity\TaskSettings;
use Maggie\Core\Entity\User;
use Symfony\Bundle\FrameworkBundle\KernelBrowser;
use Symfony\Bundle\FrameworkBundle\Test\WebTestCase;
use Symfony\Component\Clock\Clock;
use Symfony\Component\Clock\MockClock;
use Symfony\Component\Clock\NativeClock;

/** The morning proposal over REST (MAG-498): five tasks by a simple rule, written nowhere until « C'est parti ». */
class DayProposalApiTest extends WebTestCase
{
    use FixtureLoaderTrait;
    use AuthenticatedTestTrait;
    use MercureAssertionTrait;
    use ElasticsearchAssertionTrait;

    private const string DAY = '2026-10-14';
    private const string PROPOSAL = '/api/day-plans/'.self::DAY.'/proposal';

    private KernelBrowser $client;

    protected function setUp(): void
    {
        $this->client = self::createClient();
        $this->client->disableReboot();
        $this->at(self::DAY.' 08:30:00 Europe/Paris');
        $this->loadFixtures('DayProposalApiTest.yaml');
        $this->authenticateAsUser($this->getFixture('test_user'));
        $this->resetMercure();
        $this->resetAsyncTransport();
    }

    protected function tearDown(): void
    {
        Clock::set(new NativeClock());
        parent::tearDown();
    }

    private function at(string $moment): void
    {
        Clock::set(new MockClock($moment));
    }

    private function em(): EntityManagerInterface
    {
        return self::getContainer()->get('doctrine.orm.entity_manager');
    }

    private function id(string $ref): string
    {
        return (string) $this->getFixture($ref)->getId();
    }

    /** @return array<string, mixed> */
    private function propose(string $endpoint = self::PROPOSAL): array
    {
        $this->client->request('GET', $endpoint, [], [], $this->authHeaders());

        return json_decode((string) $this->client->getResponse()->getContent(), true, 512, JSON_THROW_ON_ERROR);
    }

    /** @param list<string> $ids */
    private function put(array $ids, string $date = self::DAY, bool $confirm = true): void
    {
        $slots = array_map(static fn (string $id): array => ['type' => 'task', 'taskId' => $id], $ids);
        $this->client->request('PUT', '/api/day-plans/'.$date, [], [], $this->authHeaders() + ['CONTENT_TYPE' => 'application/json'], json_encode(['slots' => $slots, 'confirm' => $confirm], JSON_THROW_ON_ERROR));
    }

    private function stored(string $date = self::DAY): ?DayPlan
    {
        $this->em()->clear();
        $user = $this->em()->find(User::class, $this->getFixture('test_user')->getId());

        return $this->em()->getRepository(DayPlan::class)->findOneBy(['user' => $user, 'date' => new \DateTimeImmutable($date)]);
    }

    /**
     * @param list<array<string, mixed>> $entries
     *
     * @return list<string>
     */
    private static function titles(array $entries): array
    {
        return array_map(static fn (array $entry): string => $entry['task']['title'], $entries);
    }

    public function testUnauthenticatedReturns401(): void
    {
        $this->client->request('GET', self::PROPOSAL);

        self::assertResponseStatusCodeSame(401);
    }

    public function testADateThatIsNotADayIs400(): void
    {
        foreach (['demain', '2026-10-45'] as $date) {
            $this->propose('/api/day-plans/'.$date.'/proposal');

            self::assertResponseStatusCodeSame(400);
        }
    }

    /** Acceptance: 8 open tasks, 3 h free — the put-off, the two due and the critical one, shortest first. */
    public function testTheProposalHoldsThePutOffTheDueAndTheCriticalOneShortestFirst(): void
    {
        $proposal = $this->propose();

        self::assertResponseIsSuccessful();
        self::assertSame(self::DAY, $proposal['date']);
        self::assertSame(180, $proposal['freeMinutes'], '8:30-9:00 and 18:30-21:00; the overlap counts once and the whole-day event takes no time');
        self::assertSame(
            ['Signer la décharge', 'Écrire à Léa', 'Envoyer le devis', 'Rappeler le garage', 'Déclarer les impôts'],
            self::titles($proposal['slots']),
            'the 5 minutes, the 15 of the best undated task, the 20, the 40, the 60',
        );
        self::assertSame([1, 2, 3, 4, 5], array_column($proposal['slots'], 'position'));
        self::assertSame(['dueToday', 'quick', 'dueToday', 'postponed', 'urgent'], array_column($proposal['slots'], 'reason'));
        self::assertSame([5, 15, 20, 40, 60], array_column($proposal['slots'], 'minutes'));
        self::assertSame(140, $proposal['totalMinutes']);
        self::assertSame($this->id('due_short'), $proposal['slots'][0]['task']['id']);
        self::assertSame(['Trier le courrier', 'Arroser les plantes', 'Ranger le garage'], self::titles($proposal['reserveRanked']), 'the others, best first: no subtask, no done task, nobody else\'s');
        self::assertSame(['quick', 'quick', 'quick'], array_column($proposal['reserveRanked'], 'reason'));
    }

    public function testTheProposalWritesNothing(): void
    {
        $this->propose();

        self::assertResponseIsSuccessful();
        self::assertNull($this->stored(), 'not even the empty day');
        self::assertSame([], $this->em()->getRepository(DayPlan::class)->findAll());
        $this->assertMercureUpdateCount(0);
        $this->assertNoElasticsearchIndexDispatched(DayPlan::class);
    }

    public function testWithAnEveningLeftThereAreFewerThanFiveAndAtLeastOne(): void
    {
        $this->at(self::DAY.' 19:50:00 Europe/Paris');

        $proposal = $this->propose();

        self::assertSame(70, $proposal['freeMinutes']);
        self::assertSame(['Signer la décharge', 'Envoyer le devis', 'Rappeler le garage'], self::titles($proposal['slots']), 'the critical one, 60 minutes, no longer fits after 40 + 20 + 5');
        self::assertSame(65, $proposal['totalMinutes']);
    }

    public function testWhenNothingFitsTheFirstTaskIsProposedAnyway(): void
    {
        $this->at(self::DAY.' 20:50:00 Europe/Paris');

        $proposal = $this->propose();

        self::assertSame(10, $proposal['freeMinutes']);
        self::assertSame(['Rappeler le garage'], self::titles($proposal['slots']));
        self::assertSame(40, $proposal['totalMinutes']);
    }

    public function testAfterNineInTheEveningThereIsNoFreeTimeLeftButOneTask(): void
    {
        $this->at(self::DAY.' 21:30:00 Europe/Paris');

        $proposal = $this->propose();

        self::assertSame(0, $proposal['freeMinutes']);
        self::assertCount(1, $proposal['slots']);
    }

    public function testTheMorningTimeOfTheSettingsOpensTheFreeTime(): void
    {
        $this->at(self::DAY.' 05:00:00 Europe/Paris');
        $this->em()->persist((new TaskSettings())->setUser($this->em()->find(User::class, $this->getFixture('test_user')->getId()))->setMorningTime('10:00'));
        $this->em()->flush();

        // 10:00-18:30 is taken: only 18:30-21:00 is left.
        self::assertSame(150, $this->propose()['freeMinutes']);
    }

    /** Acceptance: a task written the day before for today is kept, and counts in the five. */
    public function testASlotAlreadyWrittenForTheDayIsKeptAndCountsInTheFive(): void
    {
        $this->put([$this->id('free_3')], confirm: false);
        self::assertResponseIsSuccessful();
        $this->resetMercure();

        $proposal = $this->propose();

        self::assertCount(5, $proposal['slots']);
        self::assertContains('Ranger le garage', self::titles($proposal['slots']));
        self::assertNotContains('Ranger le garage', self::titles($proposal['reserveRanked']));
        self::assertSame(
            ['Signer la décharge', 'Ranger le garage', 'Envoyer le devis', 'Rappeler le garage', 'Déclarer les impôts'],
            self::titles($proposal['slots']),
            'the put-off, the two due and the critical one fill the other four; kept, it is not left out for a better one',
        );
        $this->assertMercureUpdateCount(0);
        self::assertSame([$this->id('free_3')], $this->stored()?->getTaskIds(), 'the proposal did not touch the written day');
    }

    /** Acceptance: swap the 2nd for the first of the reserve, then « C'est parti » writes the day settled. */
    public function testSwappingTheSecondThenGoingWritesTheDaySettled(): void
    {
        $proposal = $this->propose();
        $ids = array_map(static fn (array $slot): string => $slot['task']['id'], $proposal['slots']);
        $ids[1] = $proposal['reserveRanked'][0]['task']['id'];

        $this->put($ids);

        self::assertResponseIsSuccessful();
        $stored = $this->stored();
        self::assertNotNull($stored?->getConfirmedAt(), '« C\'est parti » settles the day');
        self::assertSame($ids, $stored->getTaskIds());
        self::assertSame($this->id('free_1'), $stored->getTaskIds()[1]);
        $this->assertMercureUpdatePublished('/api/day_plans/'.$stored->getId());
        $this->assertNoElasticsearchIndexDispatched(DayPlan::class);
    }

    public function testAnotherUsersTasksAreNeverProposed(): void
    {
        $proposal = $this->propose();

        self::assertNotContains("Someone else's", array_merge(self::titles($proposal['slots']), self::titles($proposal['reserveRanked'])));
    }

    public function testTomorrowsProposalMeasuresDueAgainstThatDay(): void
    {
        $proposal = $this->propose('/api/day-plans/2026-10-15/proposal');

        self::assertSame('2026-10-15', $proposal['date']);
        self::assertSame(780, $proposal['freeMinutes'], 'no event tomorrow: from the morning time, 8:00, to 21:00');
        self::assertNull($this->stored('2026-10-15'));
    }
}
