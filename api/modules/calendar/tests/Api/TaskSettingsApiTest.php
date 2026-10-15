<?php

namespace Maggie\Calendar\Tests\Api;

use App\Tests\Support\AuthenticatedTestTrait;
use App\Tests\Support\ElasticsearchAssertionTrait;
use App\Tests\Support\FixtureLoaderTrait;
use App\Tests\Support\MercureAssertionTrait;
use Maggie\Calendar\Entity\TaskSettings;
use Maggie\Calendar\Service\TaskAlertSchedule;
use Maggie\Calendar\Service\TaskOrderWeights;
use Maggie\Core\Entity\User;
use PHPUnit\Framework\Attributes\DataProvider;
use Symfony\Bundle\FrameworkBundle\KernelBrowser;
use Symfony\Bundle\FrameworkBundle\Test\WebTestCase;
use Symfony\Component\Clock\Clock;
use Symfony\Component\Clock\MockClock;
use Symfony\Component\Clock\NativeClock;

/** The task settings over REST — the order weights (MAG-406), the alert schedule and the morning time (MAG-407) — and the order of « Aujourd'hui » their weights set. */
class TaskSettingsApiTest extends WebTestCase
{
    use FixtureLoaderTrait;
    use AuthenticatedTestTrait;
    use MercureAssertionTrait;
    use ElasticsearchAssertionTrait;

    private const string ENDPOINT = '/api/task-settings/me';

    private KernelBrowser $client;

    protected function setUp(): void
    {
        $this->client = self::createClient();
        Clock::set(new MockClock('2026-10-13 12:00:00 UTC'));
        $this->loadFixtures('TaskSettingsApiTest.yaml');
        $this->authenticateAsUser($this->getFixture('test_user'));
        $this->resetMercure();
        $this->resetAsyncTransport();
    }

    protected function tearDown(): void
    {
        Clock::set(new NativeClock());
        parent::tearDown();
    }

    /** @return array<string, mixed> */
    private function put(string $body): array
    {
        $this->client->request('PUT', self::ENDPOINT, [], [], $this->authHeaders() + ['CONTENT_TYPE' => 'application/json'], $body);

        return json_decode((string) $this->client->getResponse()->getContent(), true, 512, JSON_THROW_ON_ERROR);
    }

    /** @return list<array<string, mixed>> */
    private function today(): array
    {
        $this->client->request('GET', '/api/tasks/view/today', [], [], ['HTTP_ACCEPT' => 'application/ld+json'] + $this->authHeaders());
        self::assertResponseIsSuccessful();

        return json_decode((string) $this->client->getResponse()->getContent(), true, 512, JSON_THROW_ON_ERROR)['member'];
    }

    private function settingsOf(string $userRef): ?TaskSettings
    {
        $em = self::getContainer()->get('doctrine.orm.entity_manager');
        $em->clear();
        $user = $this->getFixture($userRef);
        \assert($user instanceof User);

        return $em->getRepository(TaskSettings::class)->findOneBy(['user' => $user->getId()]);
    }

    public function testUnauthenticatedReturns401(): void
    {
        $this->client->request('GET', self::ENDPOINT);
        self::assertResponseStatusCodeSame(401);

        $this->client->request('PUT', self::ENDPOINT, [], [], ['CONTENT_TYPE' => 'application/json'], '{"orderWeights": {"priority": {"high": 9}}}');
        self::assertResponseStatusCodeSame(401);
    }

    public function testTheSettingsAreCreatedOnFirstReadWithTheSpecWeights(): void
    {
        self::assertNull($this->settingsOf('test_user'));

        $this->client->request('GET', self::ENDPOINT, [], [], $this->authHeaders());

        self::assertResponseIsSuccessful();
        $data = json_decode((string) $this->client->getResponse()->getContent(), true, 512, JSON_THROW_ON_ERROR);
        $created = $this->settingsOf('test_user');
        self::assertNotNull($created);
        self::assertSame((string) $created->getId(), $data['id']);
        self::assertSame(TaskOrderWeights::DEFAULTS, $data['orderWeights']);
        self::assertSame(65, $data['orderWeights']['timePressure']['overdue']);
        self::assertSame(TaskAlertSchedule::DEFAULTS, $data['alertSchedule']);
        self::assertSame([3, 1], $data['alertSchedule']['critical']['daysBefore']);
        self::assertSame(2, $data['alertSchedule']['normal']['overdueEveryDays']);
        self::assertSame('08:00', $data['morningTime']);
        self::assertSame(['orderWeights' => TaskOrderWeights::DEFAULTS, 'alertSchedule' => TaskAlertSchedule::DEFAULTS, 'morningTime' => '08:00'], $data['defaults']);
        $this->assertMercurePublishedOnce('/api/task_settings/'.$created->getId());
    }

    public function testPutChangesTheWeightsSentKeepsTheOthersAndPublishes(): void
    {
        $data = $this->put('{"orderWeights": {"priority": {"high": 9}, "timePressure": {"today": 50}}}');

        self::assertResponseIsSuccessful();
        self::assertSame(9, $data['orderWeights']['priority']['high']);
        self::assertSame(50, $data['orderWeights']['timePressure']['today']);
        self::assertSame(3, $data['orderWeights']['priority']['medium']);

        $stored = $this->settingsOf('test_user');
        self::assertNotNull($stored);
        self::assertSame(9, $stored->getOrderWeights()['priority']['high']);
        self::assertSame(45, $stored->getOrderWeights()['timePressure']['tomorrow']);
        $this->assertMercureUpdatePublished('/api/task_settings/'.$stored->getId());
        $payloads = $this->mercurePayloadsOn('/api/task_settings/'.$stored->getId());
        self::assertSame(9, end($payloads)['orderWeights']['priority']['high']);
        // The settings are not searched: nothing is indexed.
        $this->assertNoElasticsearchIndexDispatched(TaskSettings::class);
        self::assertSame(0, $this->settingsOf('other_user')?->getOrderWeights()['timePressure']['overdue']);
    }

    public function testPutChangesTheScheduleAndTheMorningAndPublishes(): void
    {
        $data = $this->put('{"morningTime": "07:30", "alertSchedule": {"critical": {"daysBefore": [1]}, "normal": {"overdueEveryDays": null}}}');

        self::assertResponseIsSuccessful();
        self::assertSame('07:30', $data['morningTime']);
        self::assertSame([1], $data['alertSchedule']['critical']['daysBefore']);
        self::assertTrue($data['alertSchedule']['critical']['dueDay'], 'a key not sent keeps its value');
        self::assertNull($data['alertSchedule']['normal']['overdueEveryDays']);
        self::assertSame(TaskOrderWeights::DEFAULTS, $data['orderWeights'], 'a field not sent keeps its value');

        $stored = $this->settingsOf('test_user');
        self::assertNotNull($stored);
        self::assertSame('07:30', $stored->getMorningTime());
        self::assertSame(['daysBefore' => [1], 'dueDay' => true, 'overdueEveryDays' => 1], $stored->getAlertSchedule()['critical']);
        self::assertSame(TaskAlertSchedule::DEFAULTS['high'], $stored->getAlertSchedule()['high']);
        self::assertSame('08:00', $this->settingsOf('other_user')?->getMorningTime());

        $topic = '/api/task_settings/'.$stored->getId();
        $this->assertMercureUpdatePublished($topic);
        $payloads = $this->mercurePayloadsOn($topic);
        self::assertSame('07:30', end($payloads)['morningTime']);
        self::assertSame([1], end($payloads)['alertSchedule']['critical']['daysBefore']);
        // The settings are not searched: nothing is indexed.
        $this->assertNoElasticsearchIndexDispatched(TaskSettings::class);
    }

    public function testTheDefaultsSentBackRestoreTheSettings(): void
    {
        $this->put('{"morningTime": "06:15", "alertSchedule": {"high": {"dueDay": false}}, "orderWeights": {"priority": {"high": 9}}}');
        self::assertResponseIsSuccessful();
        $this->client->request('GET', self::ENDPOINT, [], [], $this->authHeaders());
        $defaults = json_decode((string) $this->client->getResponse()->getContent(), true, 512, JSON_THROW_ON_ERROR)['defaults'];

        $data = $this->put(json_encode($defaults, JSON_THROW_ON_ERROR));

        self::assertResponseIsSuccessful();
        self::assertSame($defaults, ['orderWeights' => $data['orderWeights'], 'alertSchedule' => $data['alertSchedule'], 'morningTime' => $data['morningTime']]);
        self::assertSame('08:00', $this->settingsOf('test_user')?->getMorningTime());
    }

    /** @return iterable<string, array{string, string}> */
    public static function badBodies(): iterable
    {
        yield 'an invalid morning time' => ['{"morningTime": "25:00"}', 'morningTime'];
        yield 'a morning time without minutes' => ['{"morningTime": "8"}', 'morningTime'];
        yield 'a morning time that is not a string' => ['{"morningTime": 8}', 'morningTime'];
        yield 'an unknown urgency' => ['{"alertSchedule": {"urgent": {"dueDay": true}}}', 'urgent'];
        yield 'the low urgency' => ['{"alertSchedule": {"low": {"dueDay": true}}}', 'low'];
        yield 'negative days before' => ['{"alertSchedule": {"critical": {"daysBefore": [-3]}}}', 'alertSchedule.critical.daysBefore'];
        yield 'a negative period late' => ['{"alertSchedule": {"normal": {"overdueEveryDays": -1}}}', 'alertSchedule.normal.overdueEveryDays'];
        yield 'a schedule that is not an object' => ['{"alertSchedule": 3}', 'alertSchedule'];
        yield 'a good field with a bad one changes neither' => ['{"morningTime": "07:00", "alertSchedule": {"high": {"dueDay": "yes"}}}', 'alertSchedule.high.dueDay'];
        yield 'a negative weight' => ['{"orderWeights": {"priority": {"high": -1}}}', 'orderWeights.priority.high'];
        yield 'an unknown weight' => ['{"orderWeights": {"timePressure": {"yesterday": 3}}}', 'yesterday'];
        yield 'an unknown group' => ['{"orderWeights": {"urgency": {"high": 3}}}', 'urgency'];
        yield 'a decimal weight' => ['{"orderWeights": {"criticality": {"high": 2.5}}}', 'orderWeights.criticality.high'];
        yield 'a string weight' => ['{"orderWeights": {"criticality": {"high": "20"}}}', 'orderWeights.criticality.high'];
        yield 'a group that is not an object' => ['{"orderWeights": {"priority": 6}}', 'orderWeights.priority'];
        yield 'weights that are not an object' => ['{"orderWeights": 6}', 'orderWeights'];
        yield 'an unknown field' => ['{"orderWeights": {}, "colour": "red"}', 'colour'];
        yield 'no field' => ['{}', 'orderWeights'];
        yield 'not JSON' => ['weights', 'orderWeights'];
    }

    #[DataProvider('badBodies')]
    public function testABadBodyIsA400AndChangesNothing(string $body, string $named): void
    {
        $this->client->request('GET', self::ENDPOINT, [], [], $this->authHeaders());
        $this->resetMercure();

        $data = $this->put($body);

        self::assertResponseStatusCodeSame(400);
        self::assertStringContainsString($named, $data['error']);
        $stored = $this->settingsOf('test_user');
        self::assertSame(TaskOrderWeights::DEFAULTS, $stored?->getOrderWeights());
        self::assertSame(TaskAlertSchedule::DEFAULTS, $stored->getAlertSchedule());
        self::assertSame('08:00', $stored->getMorningTime());
        $this->assertMercureUpdateCount(0);
    }

    public function testTodayIsInScoreOrderWithTheUrgencyOfEachTask(): void
    {
        $members = $this->today();

        self::assertSame(['Payer la cantine', 'Rendre le livre', 'Arroser'], array_column($members, 'title'));
        self::assertSame([78, 67, 55], array_column($members, 'orderScore'));
        self::assertSame(['high', 'high', 'normal'], array_column($members, 'urgency'));
    }

    public function testChangingAWeightChangesTheOrderOfTheView(): void
    {
        // One day late now weighs less than a high criticality due today without it.
        $this->put('{"orderWeights": {"timePressure": {"overdue": 10, "overduePerDay": 0}, "criticality": {"high": 0}}}');
        self::assertResponseIsSuccessful();

        $members = $this->today();

        self::assertSame(['Payer la cantine', 'Arroser', 'Rendre le livre'], array_column($members, 'title'));
        self::assertSame([58, 55, 10], array_column($members, 'orderScore'));
        self::assertSame(['high', 'normal', 'high'], array_column($members, 'urgency'), 'the weights order, they never change the urgency');
    }

    public function testTheItemAndTheCollectionCarryTheUrgencyAndTheScore(): void
    {
        $canteen = $this->getFixture('pay_the_canteen');

        $this->client->request('GET', '/api/tasks/'.$canteen->getId(), [], [], ['HTTP_ACCEPT' => 'application/ld+json'] + $this->authHeaders());
        self::assertResponseIsSuccessful();
        $item = json_decode((string) $this->client->getResponse()->getContent(), true, 512, JSON_THROW_ON_ERROR);
        self::assertSame('high', $item['urgency']);
        self::assertSame(78, $item['orderScore']);

        $this->client->request('GET', '/api/tasks?pagination=false', [], [], ['HTTP_ACCEPT' => 'application/ld+json'] + $this->authHeaders());
        self::assertResponseIsSuccessful();
        $collection = json_decode((string) $this->client->getResponse()->getContent(), true, 512, JSON_THROW_ON_ERROR);
        $byTitle = array_column($collection['member'], null, 'title');
        self::assertSame('normal', $byTitle['Arroser']['urgency']);
        self::assertSame(67, $byTitle['Rendre le livre']['orderScore']);
    }

    public function testReadingTheTasksCreatesNoSettings(): void
    {
        $this->today();

        self::assertNull($this->settingsOf('test_user'));
        $this->assertMercureUpdateCount(0);
    }

    public function testTheUrgencyAndTheScoreCannotBeWritten(): void
    {
        $water = $this->getFixture('water');

        $this->client->request('PATCH', '/api/tasks/'.$water->getId(), [], [], [
            'CONTENT_TYPE' => 'application/merge-patch+json',
            'HTTP_ACCEPT' => 'application/ld+json',
        ] + $this->authHeaders(), json_encode(['urgency' => 'critical', 'orderScore' => 999], JSON_THROW_ON_ERROR));

        self::assertResponseIsSuccessful();
        $data = json_decode((string) $this->client->getResponse()->getContent(), true, 512, JSON_THROW_ON_ERROR);
        self::assertSame('normal', $data['urgency']);
        self::assertSame(55, $data['orderScore']);
    }
}
