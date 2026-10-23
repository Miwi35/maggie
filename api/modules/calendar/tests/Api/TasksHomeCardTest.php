<?php

declare(strict_types=1);

namespace Maggie\Calendar\Tests\Api;

use App\Tests\Support\AuthenticatedTestTrait;
use App\Tests\Support\ElasticsearchAssertionTrait;
use App\Tests\Support\FixtureLoaderTrait;
use App\Tests\Support\MercureAssertionTrait;
use Doctrine\ORM\EntityManagerInterface;
use Maggie\Calendar\Entity\Task;
use Symfony\Bundle\FrameworkBundle\KernelBrowser;
use Symfony\Bundle\FrameworkBundle\Test\WebTestCase;
use Symfony\Component\Clock\Clock;
use Symfony\Component\Clock\MockClock;
use Symfony\Component\Clock\NativeClock;

/**
 * GET /api/home/tasks (MAG-561): the Tâches card of the Accueil — the day's
 * tasks, the routines of the moment — and the refresh signal ticking sends.
 */
class TasksHomeCardTest extends WebTestCase
{
    use FixtureLoaderTrait;
    use AuthenticatedTestTrait;
    use MercureAssertionTrait;
    use ElasticsearchAssertionTrait;

    private KernelBrowser $client;

    protected function setUp(): void
    {
        $this->client = self::createClient();
        $this->client->disableReboot();
        $this->resetMercure();
        $this->resetAsyncTransport();
        Clock::set(new MockClock('2026-10-20 10:00:00 UTC'));
        $this->loadFixtures('TasksHomeCardTest.yaml');
        $this->authenticateAsUser($this->getFixture('test_user'));
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

    /** @return array<string, mixed> */
    private function card(string $query = ''): array
    {
        $this->client->request('GET', '/api/home/tasks'.$query, [], [], $this->authHeaders());
        self::assertResponseIsSuccessful();

        return json_decode((string) $this->client->getResponse()->getContent(), true, 512, JSON_THROW_ON_ERROR);
    }

    /** @param array<string, mixed> $body */
    private function patch(string $ref, array $body): void
    {
        $this->client->request('PATCH', '/api/tasks/'.$this->id($ref), [], [], array_merge([
            'CONTENT_TYPE' => 'application/merge-patch+json',
            'HTTP_ACCEPT' => 'application/ld+json',
        ], $this->authHeaders()), json_encode($body, JSON_THROW_ON_ERROR));
        self::assertResponseIsSuccessful();
    }

    private function id(string $ref): string
    {
        return (string) $this->getFixture($ref)->getId();
    }

    public function testUnauthenticatedIs401(): void
    {
        $this->client->request('GET', '/api/home/tasks');

        self::assertResponseStatusCodeSame(401);
    }

    public function testInvalidDayIs400(): void
    {
        $this->client->request('GET', '/api/home/tasks?day=yesterday', [], [], $this->authHeaders());

        self::assertResponseStatusCodeSame(400);
    }

    public function testTodayHoldsWhatIsLeftThenWhatWasTickedToday(): void
    {
        $body = $this->card();

        self::assertSame(['tasks', 'today', true], [$body['module'], $body['day'], $body['editable']]);
        self::assertSame(
            [['En retard', false], ['Du jour', false], ['Du jour aussi', false], ['Faite après minuit', true]],
            array_map(static fn (array $row): array => [$row['title'], $row['done']], $body['tasks']),
        );
        self::assertSame([1, 4, 0, 1], [$body['done'], $body['total'], $body['more'], $body['overdue']]);
        self::assertSame(2, $body['tasks'][0]['overdueDays']);
        self::assertNull($body['tasks'][1]['overdueDays']);
        self::assertNull($body['tasks'][3]['overdueDays']);
        self::assertNull($body['tasks'][0]['parentId']);
        self::assertNull($body['saga']);
    }

    public function testNeverMoreThanFiveRowsAndTheRestIsCounted(): void
    {
        $user = $this->getFixture('test_user');
        foreach (range(1, 4) as $n) {
            $this->em()->persist((new Task())->setUser($user)->setTitle('Extra '.$n)->setDueDate(new \DateTimeImmutable('2026-10-20')));
        }
        $this->em()->flush();

        $body = $this->card();

        // Seven open tasks and one ticked: the ticked row keeps its place among five.
        self::assertCount(5, $body['tasks']);
        self::assertSame([1, 5, 3, 1], [$body['done'], $body['total'], $body['more'], $body['overdue']]);
        self::assertTrue($body['tasks'][4]['done']);
    }

    public function testTickingATaskUpdatesTheCardAndTellsTheHomePage(): void
    {
        $this->patch('due_today', ['completedAt' => '2026-10-20T10:00:00+00:00']);

        $this->assertMercurePublishedOnce('/home/tasks');
        $body = $this->card();
        self::assertSame([2, 4], [$body['done'], $body['total']]);
        $done = array_filter($body['tasks'], static fn (array $row): bool => $row['done']);
        self::assertEqualsCanonicalizing(['Faite après minuit', 'Du jour'], array_column($done, 'title'));
    }

    public function testUntickingPutsTheTaskBackInTheOpenRows(): void
    {
        $this->patch('done_after_midnight', ['completedAt' => null]);

        $body = $this->card();
        self::assertSame([0, 4], [$body['done'], $body['total']]);
        $this->assertMercurePublishedOnce('/home/tasks');
    }

    public function testRoutinesAreThoseOfTheCurrentMomentOnly(): void
    {
        $routines = $this->card()['routines'];

        self::assertSame(['Vitamines'], array_column($routines, 'title'));
        self::assertSame(['afterBreakfast', '08:30', false], [$routines[0]['anchor'], $routines[0]['time'], $routines[0]['checked']]);
    }

    public function testTickingARoutineCreatesTodaysCheckAndTellsTheHomePage(): void
    {
        $id = $this->id('breakfast_daily');

        $this->client->request('PUT', '/api/routines/'.$id.'/checks/2026-10-20', [], [], $this->authHeaders());
        self::assertResponseIsSuccessful();

        $this->assertMercurePublishedOnce('/home/tasks');
        self::assertTrue($this->card()['routines'][0]['checked']);

        $this->resetMercure();
        $this->client->request('DELETE', '/api/routines/'.$id.'/checks/2026-10-20', [], [], $this->authHeaders());
        self::assertResponseIsSuccessful();
        $this->assertMercurePublishedOnce('/home/tasks');
        self::assertFalse($this->card()['routines'][0]['checked']);
    }

    public function testTheMomentFollowsTheClock(): void
    {
        Clock::set(new MockClock('2026-10-20 03:00:00 UTC'));
        self::assertSame(['Grand verre d\'eau'], array_column($this->card()['routines'], 'title'));

        Clock::set(new MockClock('2026-10-20 19:00:00 UTC'));
        self::assertSame(['Lave-vaisselle'], array_column($this->card()['routines'], 'title'));
    }

    public function testTomorrowHoldsTomorrowsTasksAndRoutinesAndCannotBeTicked(): void
    {
        $body = $this->card('?day=tomorrow');

        self::assertSame(['tomorrow', false], [$body['day'], $body['editable']]);
        self::assertSame(['Pour demain'], array_column($body['tasks'], 'title'));
        self::assertSame([0, 1, 0, 0], [$body['done'], $body['total'], $body['more'], $body['overdue']]);
        // Wednesday: the weekly routine is due, and every moment of the day shows.
        self::assertSame(
            ['Grand verre d\'eau', 'Arroser les plantes', 'Vitamines', 'Lave-vaisselle'],
            array_column($body['routines'], 'title'),
        );
        self::assertSame([false, false, false, false], array_column($body['routines'], 'checked'));
    }

    public function testTheCardIsTheUsersOwn(): void
    {
        $this->authenticateAsUser($this->getFixture('other_user'));

        $body = $this->card();

        self::assertSame(['Tâche d\'un autre'], array_column($body['tasks'], 'title'));
        self::assertSame(['Méditer'], array_column($body['routines'], 'title'));
    }

    public function testReadingPublishesNothing(): void
    {
        $this->card();
        $this->card('?day=tomorrow');

        $this->assertMercureUpdateCount(0);
    }
}
