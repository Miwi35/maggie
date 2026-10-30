<?php

declare(strict_types=1);

namespace Maggie\Calendar\Tests\Api;

use App\Tests\Support\AuthenticatedTestTrait;
use App\Tests\Support\ElasticsearchAssertionTrait;
use App\Tests\Support\FixtureLoaderTrait;
use App\Tests\Support\MercureAssertionTrait;
use Maggie\Calendar\Entity\Task;
use Maggie\Calendar\Enum\TaskMoment;
use PHPUnit\Framework\Attributes\DataProvider;
use Symfony\Bundle\FrameworkBundle\KernelBrowser;
use Symfony\Bundle\FrameworkBundle\Test\WebTestCase;

/**
 * The programmation of a task over HTTP (MAG-718): a day planned, a moment of
 * it, a time and reminders only at a precise time — apart from the due day.
 */
final class TaskScheduleApiTest extends WebTestCase
{
    use FixtureLoaderTrait;
    use AuthenticatedTestTrait;
    use MercureAssertionTrait;
    use ElasticsearchAssertionTrait;

    private KernelBrowser $client;

    protected function setUp(): void
    {
        $this->client = self::createClient();
        $this->loadFixtures('TaskApiTest.yaml');
        $this->authenticateAsUser($this->getFixture('test_user'));
        $this->resetMercure();
        $this->resetAsyncTransport();
    }

    public function testPlanningATaskNeedsAUser(): void
    {
        $this->client->request('POST', '/api/tasks', [], [], ['CONTENT_TYPE' => 'application/ld+json'], json_encode(['title' => 'X', 'scheduledDate' => '2037-01-02'], JSON_THROW_ON_ERROR));

        self::assertResponseStatusCodeSame(401);
    }

    /** @return iterable<string, array{string, string|null, list<int>}> */
    public static function moments(): iterable
    {
        yield 'morning' => ['morning', null, []];
        yield 'afternoon' => ['afternoon', null, []];
        yield 'evening' => ['evening', null, []];
        yield 'time, its default reminder at the time' => ['time', '18:00', [0]];
    }

    /** @param list<int> $reminders */
    #[DataProvider('moments')]
    public function testATaskIsPlannedOnADayAtAMoment(string $moment, ?string $time, array $reminders): void
    {
        $this->post(['title' => 'Préparer le weekend', 'scheduledDate' => '2037-01-02', 'scheduledMoment' => $moment] + (null === $time ? [] : ['scheduledTime' => $time]));

        self::assertResponseStatusCodeSame(201);
        $json = $this->json();
        self::assertSame('2037-01-02', $json['scheduledDate']);
        self::assertSame($moment, $json['scheduledMoment']);
        self::assertSame($time, $json['scheduledTime'] ?? null);
        self::assertSame($reminders, $json['reminders']);
        self::assertNull($json['dueDate'] ?? null, 'planned is not due');

        $task = $this->reload((string) $json['id']);
        self::assertSame('2037-01-02', $task->getScheduledDate()?->format('Y-m-d'));
        self::assertSame(TaskMoment::from($moment), $task->getScheduledMoment());
        self::assertSame($time, $task->getScheduledTime());
        self::assertSame($reminders, $task->getReminders());
        $this->assertMercureUpdatePublished('/tasks/');
        $this->assertElasticsearchIndexDispatched(Task::class);
    }

    public function testAPreciseTimeKeepsTheRemindersGiven(): void
    {
        $this->post(['title' => 'Kiné', 'scheduledDate' => '2037-01-02', 'scheduledMoment' => 'time', 'scheduledTime' => '09:15', 'reminders' => [60, 30, 30]]);

        self::assertResponseStatusCodeSame(201);
        self::assertSame([30, 60], $this->reload((string) $this->json()['id'])->getReminders());
    }

    public function testRemindersSentEmptyAtAPreciseTimeLeaveNone(): void
    {
        $this->post(['title' => 'Sans rappel', 'scheduledDate' => '2037-01-02', 'scheduledMoment' => 'time', 'scheduledTime' => '18:00', 'reminders' => []]);
        self::assertResponseStatusCodeSame(201);
        self::assertSame([], $this->reload((string) $this->json()['id'])->getReminders(), 'on create');

        $task = $this->getFixture('task_moving');
        $this->patch($task, ['scheduledDate' => '2037-01-02', 'scheduledMoment' => 'evening']);
        self::assertResponseIsSuccessful();
        $this->patch($task, ['scheduledMoment' => 'time', 'scheduledTime' => '18:00', 'reminders' => []]);
        self::assertResponseIsSuccessful();
        self::assertSame([], $this->reload((string) $task->getId())->getReminders(), 'from the evening to a precise time');
    }

    /** @return iterable<string, array{array<string, mixed>}> */
    public static function incoherent(): iterable
    {
        yield 'a time with the evening' => [['scheduledDate' => '2037-01-02', 'scheduledMoment' => 'evening', 'scheduledTime' => '18:00']];
        yield 'reminders with the evening' => [['scheduledDate' => '2037-01-02', 'scheduledMoment' => 'evening', 'reminders' => [30]]];
        yield 'a time without a moment' => [['scheduledDate' => '2037-01-02', 'scheduledTime' => '18:00']];
        yield 'reminders without a moment' => [['scheduledDate' => '2037-01-02', 'reminders' => [0]]];
        yield 'time without its time' => [['scheduledDate' => '2037-01-02', 'scheduledMoment' => 'time']];
        yield 'a moment without a day' => [['scheduledMoment' => 'morning']];
    }

    /** @param array<string, mixed> $schedule */
    #[DataProvider('incoherent')]
    public function testAnIncoherentProgrammationIsRefused(array $schedule): void
    {
        $this->post(['title' => 'Refusée'] + $schedule);

        self::assertResponseStatusCodeSame(400);
        self::assertNull($this->em()->getRepository(Task::class)->findOneBy(['title' => 'Refusée']));
        $this->assertMercureUpdateCount(0);
    }

    public function testAReminderBeyondAWeekOrATimeThatIsNotOneIsRefused(): void
    {
        $this->post(['title' => 'Refusée', 'scheduledDate' => '2037-01-02', 'scheduledMoment' => 'time', 'scheduledTime' => '18:00', 'reminders' => [10081]]);
        self::assertResponseStatusCodeSame(422);

        $this->post(['title' => 'Refusée', 'scheduledDate' => '2037-01-02', 'scheduledMoment' => 'time', 'scheduledTime' => '25:00']);
        self::assertResponseStatusCodeSame(422);

        $this->post(['title' => 'Refusée', 'scheduledDate' => '2037-01-02', 'scheduledMoment' => 'noon']);
        self::assertResponseStatusCodeSame(400);

        self::assertNull($this->em()->getRepository(Task::class)->findOneBy(['title' => 'Refusée']));
        $this->assertMercureUpdateCount(0);
    }

    public function testPatchingATimeOnATaskNotAtAPreciseTimeIsRefused(): void
    {
        $task = $this->getFixture('task_moving');

        $this->patch($task, ['scheduledDate' => '2037-01-02', 'scheduledMoment' => 'evening', 'reminders' => [30]]);
        self::assertResponseStatusCodeSame(400);
        $this->patch($task, ['scheduledTime' => '18:00']);
        self::assertResponseStatusCodeSame(400);

        self::assertNull($this->reload((string) $task->getId())->getScheduledDate());
        $this->assertMercureUpdateCount(0);
    }

    public function testAnotherMomentDropsTheTimeAndTheReminders(): void
    {
        $task = $this->getFixture('task_moving');
        $this->patch($task, ['scheduledDate' => '2037-01-02', 'scheduledMoment' => 'time', 'scheduledTime' => '18:00', 'reminders' => [30]]);
        self::assertResponseIsSuccessful();
        $this->resetMercure();

        $this->patch($task, ['scheduledMoment' => 'evening']);

        self::assertResponseIsSuccessful();
        $reloaded = $this->reload((string) $task->getId());
        self::assertSame('2037-01-02', $reloaded->getScheduledDate()?->format('Y-m-d'));
        self::assertSame(TaskMoment::Evening, $reloaded->getScheduledMoment());
        self::assertNull($reloaded->getScheduledTime());
        self::assertSame([], $reloaded->getReminders());
        self::assertSame('Les cartons sont à la cave', $reloaded->getDescription(), 'the rest untouched');
        $this->assertMercureUpdatePublished('/tasks/'.$task->getId());
        $this->assertElasticsearchIndexDispatched(Task::class);
    }

    public function testNoDayEmptiesTheWholeProgrammation(): void
    {
        $task = $this->getFixture('task_moving');
        $this->patch($task, ['scheduledDate' => '2037-01-02', 'scheduledMoment' => 'time', 'scheduledTime' => '18:00']);
        self::assertResponseIsSuccessful();

        $this->patch($task, ['scheduledDate' => null]);

        self::assertResponseIsSuccessful();
        $reloaded = $this->reload((string) $task->getId());
        self::assertNull($reloaded->getScheduledDate());
        self::assertNull($reloaded->getScheduledMoment());
        self::assertNull($reloaded->getScheduledTime());
        self::assertSame([], $reloaded->getReminders());
    }

    public function testTheItemAndTheDayFilterReadTheProgrammation(): void
    {
        $task = $this->getFixture('task_moving');
        $this->patch($task, ['scheduledDate' => '2037-01-02', 'scheduledMoment' => 'time', 'scheduledTime' => '18:00']);
        self::assertResponseIsSuccessful();

        $this->client->request('GET', '/api/tasks/'.$task->getId(), [], [], ['HTTP_ACCEPT' => 'application/ld+json'] + $this->authHeaders());
        self::assertResponseIsSuccessful();
        $json = $this->json();
        self::assertSame(['2037-01-02', 'time', '18:00', [0]], [$json['scheduledDate'], $json['scheduledMoment'], $json['scheduledTime'], $json['reminders']]);

        $this->client->request('GET', '/api/tasks?scheduledDate[after]=2037-01-02&scheduledDate[before]=2037-01-02', [], [], ['HTTP_ACCEPT' => 'application/ld+json'] + $this->authHeaders());
        self::assertResponseIsSuccessful();
        self::assertSame(['Préparer le déménagement'], array_column($this->json()['member'], 'title'));
    }

    /** @param array<string, mixed> $payload */
    private function post(array $payload): void
    {
        $this->client->request('POST', '/api/tasks', [], [], [
            'CONTENT_TYPE' => 'application/ld+json',
            'HTTP_ACCEPT' => 'application/ld+json',
        ] + $this->authHeaders(), json_encode($payload, JSON_THROW_ON_ERROR));
    }

    /** @param array<string, mixed> $payload */
    private function patch(Task $task, array $payload): void
    {
        $this->client->request('PATCH', '/api/tasks/'.$task->getId(), [], [], [
            'CONTENT_TYPE' => 'application/merge-patch+json',
            'HTTP_ACCEPT' => 'application/ld+json',
        ] + $this->authHeaders(), json_encode($payload, JSON_THROW_ON_ERROR));
    }

    /** @return array<string, mixed> */
    private function json(): array
    {
        return json_decode((string) $this->client->getResponse()->getContent(), true, 512, JSON_THROW_ON_ERROR);
    }

    private function em(): \Doctrine\ORM\EntityManagerInterface
    {
        $em = self::getContainer()->get('doctrine.orm.entity_manager');
        $em->clear();

        return $em;
    }

    private function reload(string $id): Task
    {
        $task = $this->em()->find(Task::class, $id);
        self::assertInstanceOf(Task::class, $task);

        return $task;
    }
}
