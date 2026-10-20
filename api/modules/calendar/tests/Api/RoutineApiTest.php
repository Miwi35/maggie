<?php

declare(strict_types=1);

namespace Maggie\Calendar\Tests\Api;

use App\Tests\Support\AuthenticatedTestTrait;
use App\Tests\Support\ElasticsearchAssertionTrait;
use App\Tests\Support\FixtureLoaderTrait;
use App\Tests\Support\MercureAssertionTrait;
use Doctrine\ORM\EntityManagerInterface;
use Maggie\Calendar\Entity\Routine;
use Maggie\Calendar\Entity\RoutineCheck;
use PHPUnit\Framework\Attributes\DataProvider;
use Symfony\Bundle\FrameworkBundle\KernelBrowser;
use Symfony\Bundle\FrameworkBundle\Test\WebTestCase;
use Symfony\Component\Clock\Clock;
use Symfony\Component\Clock\MockClock;
use Symfony\Component\Clock\NativeClock;

/**
 * Routines over REST (MAG-511): created, changed, deleted, ticked once a day,
 * read with what was done — the month's ticks, the total, the last seven
 * days — and never a streak.
 */
class RoutineApiTest extends WebTestCase
{
    use FixtureLoaderTrait;
    use AuthenticatedTestTrait;
    use MercureAssertionTrait;
    use ElasticsearchAssertionTrait;

    private const string TODAY = '2026-10-20';

    private KernelBrowser $client;

    protected function setUp(): void
    {
        $this->client = self::createClient();
        $this->client->disableReboot();
        Clock::set(new MockClock(self::TODAY.' 10:00:00 UTC'));
        $this->loadFixtures('RoutineApiTest.yaml');
        $this->authenticateAsUser($this->getFixture('test_user'));
        $this->resetMercure();
        $this->resetAsyncTransport();
    }

    protected function tearDown(): void
    {
        Clock::set(new NativeClock());
        parent::tearDown();
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
    private function request(string $method, string $endpoint, mixed $body = null): array
    {
        $this->client->request($method, $endpoint, [], [], $this->authHeaders() + ['CONTENT_TYPE' => 'application/json'], null === $body ? null : (\is_string($body) ? $body : json_encode($body, JSON_THROW_ON_ERROR)));
        $content = (string) $this->client->getResponse()->getContent();

        return '' === $content ? [] : json_decode($content, true, 512, JSON_THROW_ON_ERROR);
    }

    private function storedRoutine(string $id): ?Routine
    {
        $this->em()->clear();

        return $this->em()->find(Routine::class, $id);
    }

    /** @return list<string> the ticked dates, in order */
    private function storedChecks(string $routineId): array
    {
        $this->em()->clear();
        $dates = array_map(
            static fn (RoutineCheck $check): string => $check->getDate()->format('Y-m-d'),
            $this->em()->getRepository(RoutineCheck::class)->findBy(['routine' => $routineId]),
        );
        sort($dates);

        return $dates;
    }

    private function seedChecks(string $ref, string ...$dates): void
    {
        $routine = $this->em()->find(Routine::class, $this->getFixture($ref)->getId());
        foreach ($dates as $date) {
            $this->em()->persist(new RoutineCheck($routine, new \DateTimeImmutable($date)));
        }
        $this->em()->flush();
        $this->resetMercure();
    }

    /** @return array<string, mixed> */
    private function listed(string $title): array
    {
        foreach ($this->request('GET', '/api/routines')['member'] as $routine) {
            if ($routine['title'] === $title) {
                return $routine;
            }
        }
        self::fail("{$title} is not in the list.");
    }

    public function testUnauthenticatedReturns401(): void
    {
        $water = $this->id('water');
        foreach ([
            ['GET', '/api/routines'],
            ['POST', '/api/routines'],
            ['GET', '/api/routines/day/'.self::TODAY],
            ['PATCH', '/api/routines/'.$water],
            ['DELETE', '/api/routines/'.$water],
            ['PUT', "/api/routines/{$water}/checks/".self::TODAY],
            ['DELETE', "/api/routines/{$water}/checks/".self::TODAY],
        ] as [$method, $endpoint]) {
            $this->client->request($method, $endpoint, [], [], ['CONTENT_TYPE' => 'application/json'], '{"title": "x", "anchor": "wake"}');
            self::assertResponseStatusCodeSame(401, "{$method} {$endpoint}");
        }
        self::assertSame([], $this->storedChecks($water));
        self::assertCount(4, $this->em()->getRepository(Routine::class)->findAll());
    }

    public function testACreatedRoutineIsStoredPublishedAndShowsInTheMorning(): void
    {
        $created = $this->request('POST', '/api/routines', ['title' => '  Boire un café  ', 'anchor' => 'afterBreakfast', 'recurrence' => ['kind' => 'daily']]);

        self::assertResponseStatusCodeSame(201);
        $stored = $this->storedRoutine($created['id']);
        self::assertNotNull($stored);
        self::assertSame('Boire un café', $stored->getTitle());
        self::assertSame('afterBreakfast', $stored->getAnchor());
        self::assertSame(['kind' => 'daily'], $stored->getRecurrence());
        self::assertTrue($stored->isActive());
        self::assertFalse($stored->isRemind(), 'no reminder unless asked for');
        self::assertSame((string) $this->getFixture('test_user')->getId(), (string) $stored->getUser()->getId());

        self::assertSame('08:30', $created['time'], 'the default hour of a moment the user did not set');
        self::assertTrue($created['dueToday']);
        self::assertFalse($created['checkedToday']);
        self::assertSame(0, $created['monthCount']);
        self::assertSame(0, $created['totalCount']);
        self::assertSame('2026-10-20T10:00:00+00:00', $created['createdAt']);
        self::assertSame(
            [false, false, false, false, false, false, true],
            array_column($created['recentDays'], 'due'),
            'not due before it existed',
        );

        $topic = '/api/routines/'.$created['id'];
        $this->assertMercurePublishedOnce($topic);
        self::assertSame('Boire un café', $this->mercurePayloadsOn($topic)[0]['title']);
        $this->assertNoElasticsearchIndexDispatched(Routine::class);

        $day = $this->request('GET', '/api/routines/day/'.self::TODAY);
        self::assertSame(['wake', 'afterBreakfast', 'afterDinner'], array_column($day['moments'], 'anchor'));
        self::assertSame(['Boire un café'], array_column($day['moments'][1]['routines'], 'title'));
    }

    /** The first acceptance criterion: created at waking, every day; ticked, then the tick taken back. */
    public function testTickingTodayStoresACheckAndUntickingRemovesIt(): void
    {
        $created = $this->request('POST', '/api/routines', ['title' => 'Grand verre d\'eau du matin', 'anchor' => 'wake', 'recurrence' => ['kind' => 'daily']]);
        $this->resetMercure();
        $endpoint = "/api/routines/{$created['id']}/checks/".self::TODAY;

        $ticked = $this->request('PUT', $endpoint);

        self::assertResponseIsSuccessful();
        self::assertTrue($ticked['checkedToday']);
        self::assertSame(1, $ticked['monthCount']);
        self::assertSame(1, $ticked['totalCount']);
        self::assertSame([self::TODAY], $this->storedChecks($created['id']));
        $check = $this->em()->getRepository(RoutineCheck::class)->findOneBy(['routine' => $created['id']]);
        \assert($check instanceof RoutineCheck);
        $checkTopic = '/api/routine_checks/'.$check->getId();
        $this->assertMercurePublishedOnce($checkTopic);
        self::assertSame(['routine' => $created['id'], 'date' => self::TODAY], array_diff_key($this->mercurePayloadsOn($checkTopic)[0], ['@id' => true]));
        $this->assertNoElasticsearchIndexDispatched(RoutineCheck::class);

        $day = $this->request('GET', '/api/routines/day/'.self::TODAY);
        $morning = array_column($day['moments'][0]['routines'], 'checked', 'title');
        self::assertTrue($morning['Grand verre d\'eau du matin']);
        self::assertFalse($morning['Grand verre d\'eau']);

        // Ticked again: still one, nothing new to announce.
        $this->resetMercure();
        $this->request('PUT', $endpoint);
        self::assertResponseIsSuccessful();
        self::assertSame([self::TODAY], $this->storedChecks($created['id']));
        $this->assertMercureUpdateCount(0);

        $unticked = $this->request('DELETE', $endpoint);
        self::assertResponseIsSuccessful();
        self::assertFalse($unticked['checkedToday']);
        self::assertSame(0, $unticked['totalCount']);
        self::assertSame([], $this->storedChecks($created['id']));
        $this->assertMercureDeletePublished($checkTopic);

        $this->resetMercure();
        $this->request('DELETE', $endpoint);
        self::assertResponseIsSuccessful();
        $this->assertMercureUpdateCount(0);
    }

    public function testTheMonthCountsItsTicksAndTheTotalKeepsThem(): void
    {
        $this->seedChecks('water', ...array_map(static fn (int $day): string => \sprintf('2026-10-%02d', $day), range(1, 13)));

        $water = $this->listed('Grand verre d\'eau');
        self::assertSame(13, $water['monthCount']);
        self::assertSame(13, $water['totalCount']);

        Clock::set(new MockClock('2026-11-02 10:00:00 UTC'));
        $water = $this->listed('Grand verre d\'eau');
        self::assertSame(0, $water['monthCount']);
        self::assertSame(13, $water['totalCount']);
    }

    /** A day missed shows no more than a day off: a neutral dot, and no streak anywhere. */
    public function testADayMissedIsOnlyADotNotTicked(): void
    {
        $this->seedChecks('water', '2026-10-17', '2026-10-18');

        $water = $this->listed('Grand verre d\'eau');

        self::assertSame(['date' => '2026-10-19', 'due' => true, 'checked' => false], $water['recentDays'][5], 'yesterday');
        self::assertSame(['date' => '2026-10-18', 'due' => true, 'checked' => true], $water['recentDays'][4]);
        self::assertSame(self::TODAY, $water['recentDays'][6]['date']);
        self::assertCount(7, $water['recentDays']);
        self::assertSame(
            ['id', 'title', 'anchor', 'anchorTime', 'time', 'recurrence', 'active', 'remind', 'createdAt', 'checkedToday', 'dueToday', 'monthCount', 'totalCount', 'recentDays'],
            array_keys($water),
        );
        self::assertDoesNotMatchRegularExpression('/streak|s[ée]rie/i', (string) $this->client->getResponse()->getContent());
    }

    public function testAWeeklyRoutineIsDueOnItsDayOnly(): void
    {
        $trash = $this->listed('Sortir les poubelles');
        self::assertTrue($trash['dueToday'], 'Tuesday');
        self::assertSame([false, false, false, false, false, false, true], array_column($trash['recentDays'], 'due'));
        self::assertSame('19:30', $trash['time'], 'dinner at the user\'s own hour');

        $wednesday = $this->request('GET', '/api/routines/day/2026-10-21');
        self::assertNotContains('Sortir les poubelles', array_merge(...array_map(static fn (array $group): array => array_column($group['routines'], 'title'), $wednesday['moments'])));
    }

    public function testAMonthlyRoutineOnThe31stFallsOnTheLastDayOfAShorterMonth(): void
    {
        $titlesOn = fn (string $date): array => array_merge(...array_map(
            static fn (array $group): array => array_column($group['routines'], 'title'),
            $this->request('GET', '/api/routines/day/'.$date)['moments'],
        ));

        self::assertContains('Aérer la chambre', $titlesOn('2026-11-30'));
        self::assertNotContains('Aérer la chambre', $titlesOn('2026-11-29'));
        self::assertContains('Aérer la chambre', $titlesOn('2026-10-31'));
        self::assertNotContains('Aérer la chambre', $titlesOn('2026-10-30'));
        self::assertContains('Aérer la chambre', $titlesOn('2027-02-28'));
    }

    public function testTheDayIsGroupedByMomentInTheOrderOfTheDayThenByTitle(): void
    {
        $this->request('POST', '/api/routines', ['title' => 'Vitamines', 'anchor' => 'time', 'anchorTime' => '07:00', 'recurrence' => ['kind' => 'weekdays']]);
        $this->request('POST', '/api/routines', ['title' => 'Lire', 'anchor' => 'time', 'anchorTime' => '22:30']);
        $this->request('POST', '/api/routines', ['title' => 'Allumer la radio', 'anchor' => 'wake']);

        $day = $this->request('GET', '/api/routines/day/'.self::TODAY);

        self::assertSame(
            [['wake', '06:45'], ['time', '07:00'], ['afterDinner', '19:30'], ['time', '22:30']],
            array_map(static fn (array $group): array => [$group['anchor'], $group['time']], $day['moments']),
        );
        self::assertSame(['Allumer la radio', 'Grand verre d\'eau'], array_column($day['moments'][0]['routines'], 'title'), 'by title within a moment');
        self::assertNotContains('Méditer', array_column($day['moments'][0]['routines'], 'title'));

        $list = $this->request('GET', '/api/routines');
        self::assertSame(['Aérer la chambre', 'Allumer la radio', 'Grand verre d\'eau', 'Vitamines', 'Sortir les poubelles', 'Lire'], array_column($list['member'], 'title'));

        $saturday = $this->request('GET', '/api/routines/day/2026-10-24');
        self::assertNotContains('Vitamines', array_merge(...array_map(static fn (array $group): array => array_column($group['routines'], 'title'), $saturday['moments'])), 'weekdays only');
    }

    public function testTickingADayItWasNotDueCounts(): void
    {
        $ticked = $this->request('PUT', "/api/routines/{$this->id('trash')}/checks/2026-10-18");

        self::assertResponseIsSuccessful();
        self::assertSame(1, $ticked['monthCount']);
        self::assertSame(['date' => '2026-10-18', 'due' => false, 'checked' => true], $ticked['recentDays'][4]);
        self::assertSame(['2026-10-18'], $this->storedChecks($this->id('trash')));
    }

    /** @return iterable<string, array{array<string, mixed>|string, string}> */
    public static function badCreations(): iterable
    {
        yield 'at an hour without the hour' => [['title' => 'Lire', 'anchor' => 'time'], 'anchorTime'];
        yield 'an hour that is not one' => [['title' => 'Lire', 'anchor' => 'time', 'anchorTime' => '25:00'], 'anchorTime'];
        yield 'an hour with a moment' => [['title' => 'Lire', 'anchor' => 'wake', 'anchorTime' => '07:00'], 'anchorTime'];
        yield 'an unknown moment' => [['title' => 'Lire', 'anchor' => 'midnight'], 'anchor'];
        yield 'no title' => [['anchor' => 'wake'], 'title'];
        yield 'a blank title' => [['title' => '  ', 'anchor' => 'wake'], 'title'];
        yield 'weekly without its day' => [['title' => 'Lire', 'anchor' => 'wake', 'recurrence' => ['kind' => 'weekly']], 'weekday'];
        yield 'monthly on the 32nd' => [['title' => 'Lire', 'anchor' => 'wake', 'recurrence' => ['kind' => 'monthly', 'monthDay' => 32]], 'monthDay'];
        yield 'an unknown rhythm' => [['title' => 'Lire', 'anchor' => 'wake', 'recurrence' => ['kind' => 'yearly']], 'recurrence.kind'];
        yield 'remind that is not a boolean' => [['title' => 'Lire', 'anchor' => 'wake', 'remind' => 'yes'], 'remind'];
        yield 'not JSON' => ['Lire', 'JSON'];
    }

    /** @param array<string, mixed>|string $body */
    #[DataProvider('badCreations')]
    public function testABadCreationIsA400AndStoresNothing(array|string $body, string $named): void
    {
        $data = $this->request('POST', '/api/routines', $body);

        self::assertResponseStatusCodeSame(400);
        self::assertStringContainsString($named, $data['error']);
        self::assertCount(4, $this->em()->getRepository(Routine::class)->findAll());
        $this->assertMercureUpdateCount(0);
    }

    public function testTickingADayToComeOrBeforeTheRoutineIsA400(): void
    {
        $water = $this->id('water');

        $future = $this->request('PUT', "/api/routines/{$water}/checks/2026-10-21");
        self::assertResponseStatusCodeSame(400);
        self::assertStringContainsString('2026-10-21', $future['error']);

        $this->request('PUT', "/api/routines/{$water}/checks/2026-09-14");
        self::assertResponseStatusCodeSame(400);

        $this->request('PUT', "/api/routines/{$water}/checks/hier");
        self::assertResponseStatusCodeSame(400);

        self::assertSame([], $this->storedChecks($water));
        $this->assertMercureUpdateCount(0);
    }

    /** Today is the user's: at 00:30 in Paris, the 21st, while the server is still on the 20th. */
    public function testTodayIsTheUsersOwnDay(): void
    {
        Clock::set(new MockClock(self::TODAY.' 22:30:00 UTC'));

        $this->request('PUT', "/api/routines/{$this->id('water')}/checks/2026-10-21");

        self::assertResponseIsSuccessful();
        self::assertSame(['2026-10-21'], $this->storedChecks($this->id('water')));
    }

    public function testAnotherUsersRoutineIsNotFound(): void
    {
        $others = $this->id('others');

        foreach ([
            ['PATCH', "/api/routines/{$others}", ['title' => 'À moi']],
            ['DELETE', "/api/routines/{$others}", null],
            ['PUT', "/api/routines/{$others}/checks/".self::TODAY, null],
            ['DELETE', "/api/routines/{$others}/checks/".self::TODAY, null],
            ['PATCH', '/api/routines/not-a-ulid', ['title' => 'À moi']],
        ] as [$method, $endpoint, $body]) {
            $this->request($method, $endpoint, $body);
            self::assertResponseStatusCodeSame(404, "{$method} {$endpoint}");
        }

        self::assertSame('Méditer', $this->storedRoutine($others)?->getTitle());
        self::assertSame([], $this->storedChecks($others));
        self::assertNotContains('Méditer', array_column($this->request('GET', '/api/routines')['member'], 'title'));
        $this->assertMercureUpdateCount(0);
    }

    public function testAPatchChangesOnlyTheFieldsGivenAndPublishes(): void
    {
        $water = $this->id('water');

        $patched = $this->request('PATCH', '/api/routines/'.$water, ['anchor' => 'time', 'anchorTime' => '06:15', 'remind' => true]);

        self::assertResponseIsSuccessful();
        self::assertSame('06:15', $patched['time']);
        $stored = $this->storedRoutine($water);
        self::assertSame('Grand verre d\'eau', $stored?->getTitle());
        self::assertSame('time', $stored->getAnchor());
        self::assertSame('06:15', $stored->getAnchorTime());
        self::assertTrue($stored->isRemind());
        self::assertSame(['kind' => 'daily'], $stored->getRecurrence());
        $this->assertMercureUpdatePublished('/api/routines/'.$water);
        $this->assertNoElasticsearchIndexDispatched(Routine::class);

        // Back to a moment: the hour goes with it.
        $this->request('PATCH', '/api/routines/'.$water, ['anchor' => 'afterLunch']);
        self::assertResponseIsSuccessful();
        self::assertNull($this->storedRoutine($water)?->getAnchorTime());
    }

    public function testARefusedPatchChangesNothing(): void
    {
        $water = $this->id('water');

        $data = $this->request('PATCH', '/api/routines/'.$water, ['title' => 'Deux verres', 'anchor' => 'time']);

        self::assertResponseStatusCodeSame(400);
        self::assertStringContainsString('anchorTime', $data['error']);
        self::assertSame('Grand verre d\'eau', $this->storedRoutine($water)?->getTitle());
        $this->assertMercureUpdateCount(0);
    }

    /** Inactive: due no day, its ticks and totals stay. */
    public function testAnInactiveRoutineIsDueNoDayAndKeepsItsTotals(): void
    {
        $this->seedChecks('water', '2026-10-01', '2026-10-02');

        $paused = $this->request('PATCH', '/api/routines/'.$this->id('water'), ['active' => false]);

        self::assertResponseIsSuccessful();
        self::assertFalse($this->storedRoutine($this->id('water'))?->isActive());
        self::assertFalse($paused['active']);
        self::assertFalse($paused['dueToday']);
        self::assertSame([false, false, false, false, false, false, false], array_column($paused['recentDays'], 'due'));
        self::assertSame(2, $paused['monthCount']);
        self::assertSame(2, $paused['totalCount']);
        $day = $this->request('GET', '/api/routines/day/'.self::TODAY);
        self::assertNotContains('Grand verre d\'eau', array_column($day['moments'][0]['routines'], 'title'));
        self::assertContains('Grand verre d\'eau', array_column($this->request('GET', '/api/routines')['member'], 'title'), 'still listed, to be set active again');
    }

    public function testDeletingARoutineDeletesItsTicks(): void
    {
        $this->seedChecks('water', '2026-10-01', '2026-10-02');
        $checkIds = array_map(
            static fn (RoutineCheck $check): string => (string) $check->getId(),
            $this->em()->getRepository(RoutineCheck::class)->findBy(['routine' => $this->id('water')]),
        );

        $this->request('DELETE', '/api/routines/'.$this->id('water'));

        self::assertResponseStatusCodeSame(204);
        self::assertNull($this->storedRoutine($this->id('water')));
        self::assertSame([], $this->storedChecks($this->id('water')));
        $this->assertMercureDeletePublished('/api/routines/'.$this->id('water'));
        foreach ($checkIds as $checkId) {
            $this->assertMercureDeletePublished('/api/routine_checks/'.$checkId);
        }
        self::assertNotNull($this->storedRoutine($this->id('trash')));
    }
}
