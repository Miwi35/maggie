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

/** The task settings over REST — the order weights (MAG-406), the alert schedule and the morning time (MAG-407), the cheer of a task done and its tone (MAG-508) — and the order of « Aujourd'hui » their weights set. */
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
        self::assertSame('21:00', $data['eveningTime']);
        $levels = ['timePressure' => 2, 'criticality' => 2, 'priority' => 2];
        self::assertSame($levels, $data['orderLevels'], 'the spec\'s weights read at the middle level, ×1');
        self::assertTrue($data['rewardAnimation']);
        self::assertTrue($data['rewardVibration']);
        self::assertTrue($data['rewardSound']);
        self::assertSame('doux', $data['tone']);
        self::assertSame(['orderWeights' => TaskOrderWeights::DEFAULTS, 'orderLevels' => $levels, 'alertSchedule' => TaskAlertSchedule::DEFAULTS, 'morningTime' => '08:00', 'eveningTime' => '21:00', 'rewardAnimation' => true, 'rewardVibration' => true, 'rewardSound' => true, 'tone' => 'doux'], $data['defaults']);
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

    public function testPutChangesTheEveningAndPublishes(): void
    {
        $data = $this->put('{"eveningTime": "22:15"}');

        self::assertResponseIsSuccessful();
        self::assertSame('22:15', $data['eveningTime']);
        self::assertSame('08:00', $data['morningTime'], 'a field not sent keeps its value');

        $stored = $this->settingsOf('test_user');
        self::assertNotNull($stored);
        self::assertSame('22:15', $stored->getEveningTime());
        self::assertSame('21:00', $this->settingsOf('other_user')?->getEveningTime());

        $topic = '/api/task_settings/'.$stored->getId();
        $this->assertMercureUpdatePublished($topic);
        $payloads = $this->mercurePayloadsOn($topic);
        self::assertSame('22:15', end($payloads)['eveningTime']);
        $this->assertNoElasticsearchIndexDispatched(TaskSettings::class);
    }

    public function testPutChangesHowATaskDoneIsCheeredAndPublishes(): void
    {
        $data = $this->put('{"rewardVibration": false, "rewardSound": false, "tone": "drole"}');

        self::assertResponseIsSuccessful();
        self::assertFalse($data['rewardVibration']);
        self::assertFalse($data['rewardSound']);
        self::assertTrue($data['rewardAnimation'], 'a field not sent keeps its value');
        self::assertSame('drole', $data['tone']);

        $stored = $this->settingsOf('test_user');
        self::assertNotNull($stored);
        self::assertFalse($stored->isRewardVibration());
        self::assertFalse($stored->isRewardSound());
        self::assertTrue($stored->isRewardAnimation());
        self::assertSame('drole', $stored->getTone());
        self::assertSame('doux', $this->settingsOf('other_user')?->getTone());

        $topic = '/api/task_settings/'.$stored->getId();
        $this->assertMercureUpdatePublished($topic);
        $payloads = $this->mercurePayloadsOn($topic);
        self::assertSame('drole', end($payloads)['tone']);
        self::assertFalse(end($payloads)['rewardSound']);
        $this->assertNoElasticsearchIndexDispatched(TaskSettings::class);
    }

    public function testTheDefaultsSentBackRestoreTheSettings(): void
    {
        $this->put('{"morningTime": "06:15", "eveningTime": "22:30", "alertSchedule": {"high": {"dueDay": false}}, "orderWeights": {"priority": {"high": 9}}, "rewardAnimation": false, "tone": "direct"}');
        self::assertResponseIsSuccessful();
        $this->client->request('GET', self::ENDPOINT, [], [], $this->authHeaders());
        $defaults = json_decode((string) $this->client->getResponse()->getContent(), true, 512, JSON_THROW_ON_ERROR)['defaults'];

        $data = $this->put(json_encode($defaults, JSON_THROW_ON_ERROR));

        self::assertResponseIsSuccessful();
        self::assertSame($defaults, array_intersect_key($data, $defaults));
        self::assertSame('08:00', $this->settingsOf('test_user')?->getMorningTime());
        self::assertSame('21:00', $this->settingsOf('test_user')?->getEveningTime());
    }

    /** @return iterable<string, array{string, string}> */
    public static function badBodies(): iterable
    {
        yield 'an invalid morning time' => ['{"morningTime": "25:00"}', 'morningTime'];
        yield 'a morning time without minutes' => ['{"morningTime": "8"}', 'morningTime'];
        yield 'a morning time that is not a string' => ['{"morningTime": 8}', 'morningTime'];
        yield 'an invalid evening time' => ['{"eveningTime": "21:60"}', 'eveningTime'];
        yield 'an evening time that is not a string' => ['{"eveningTime": 21}', 'eveningTime'];
        yield 'a good evening with a bad morning changes neither' => ['{"eveningTime": "22:00", "morningTime": "7h"}', 'morningTime'];
        yield 'an unknown tone' => ['{"tone": "sec"}', 'tone'];
        yield 'a tone that is not a string' => ['{"tone": 2}', 'tone'];
        yield 'a sound that is not a boolean' => ['{"rewardSound": "off"}', 'rewardSound'];
        yield 'a good tone with a bad vibration changes neither' => ['{"tone": "direct", "rewardVibration": 0}', 'rewardVibration'];
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
        yield 'a level above the last' => ['{"orderLevels": {"priority": 5}}', 'orderLevels.priority'];
        yield 'a negative level' => ['{"orderLevels": {"criticality": -1}}', 'orderLevels.criticality'];
        yield 'a level that is not an integer' => ['{"orderLevels": {"timePressure": "2"}}', 'orderLevels.timePressure'];
        yield 'an unknown level group' => ['{"orderLevels": {"urgency": 2}}', 'urgency'];
        yield 'levels that are not an object' => ['{"orderLevels": [2, 2, 2]}', 'orderLevels'];
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
        self::assertSame('21:00', $stored->getEveningTime());
        self::assertSame('doux', $stored->getTone());
        self::assertTrue($stored->isRewardVibration());
        self::assertTrue($stored->isRewardSound());
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

    public function testALevelScalesTheSpecWeightsOfItsGroupAndPublishes(): void
    {
        $data = $this->put('{"orderLevels": {"timePressure": 0, "criticality": 4}}');

        self::assertResponseIsSuccessful();
        self::assertSame(['timePressure' => 0, 'criticality' => 4, 'priority' => 2], $data['orderLevels']);
        // ×0.25 and ×2.4 of the spec, rounded; the priority not sent keeps its weights.
        self::assertSame(['overdue' => 16, 'overduePerDay' => 1, 'overdueMax' => 21, 'today' => 14, 'tomorrow' => 11, 'in2To3Days' => 8, 'in4To7Days' => 4, 'in8To30Days' => 1, 'later' => 0], $data['orderWeights']['timePressure']);
        self::assertSame(['critical' => 72, 'high' => 48, 'medium' => 24, 'low' => 0], $data['orderWeights']['criticality']);
        self::assertSame(TaskOrderWeights::DEFAULTS['priority'], $data['orderWeights']['priority']);

        $stored = $this->settingsOf('test_user');
        self::assertNotNull($stored);
        self::assertSame(72, $stored->getOrderWeights()['criticality']['critical']);
        $this->assertMercureUpdatePublished('/api/task_settings/'.$stored->getId());
        $this->assertNoElasticsearchIndexDispatched(TaskSettings::class);
    }

    public function testWeightsChangedOneByOneReadAtTheNearestLevel(): void
    {
        $data = $this->put('{"orderWeights": {"priority": {"high": 14, "medium": 7}, "criticality": {"high": 21}}}');

        self::assertResponseIsSuccessful();
        self::assertSame(['timePressure' => 2, 'criticality' => 2, 'priority' => 4], $data['orderLevels']);
        self::assertSame(21, $data['orderWeights']['criticality']['high'], 'a level read is not written back');
    }

    public function testTheDefaultLevelsPutBackTheSpecWeights(): void
    {
        $this->put('{"orderLevels": {"timePressure": 4, "criticality": 0, "priority": 1}}');

        $data = $this->put('{"orderLevels": {"timePressure": 2, "criticality": 2, "priority": 2}}');

        self::assertResponseIsSuccessful();
        self::assertSame(TaskOrderWeights::DEFAULTS, $this->settingsOf('test_user')?->getOrderWeights());
        self::assertSame(TaskOrderWeights::DEFAULTS, $data['orderWeights']);
    }

    /** @return list<array<string, mixed>> */
    private function preview(): array
    {
        $this->client->request('GET', self::ENDPOINT.'/preview', [], [], $this->authHeaders());
        self::assertResponseIsSuccessful();

        return json_decode((string) $this->client->getResponse()->getContent(), true, 512, JSON_THROW_ON_ERROR)['member'];
    }

    public function testThePreviewRequiresAUser(): void
    {
        $this->client->request('GET', self::ENDPOINT.'/preview');

        self::assertResponseStatusCodeSame(401);
    }

    public function testThePreviewShowsFiveOpenTasksInTheOrderOfTheWeights(): void
    {
        $members = $this->preview();

        self::assertSame(['Payer la cantine', 'Rendre le livre', 'Arroser', 'Déclarer les impôts', 'Appeler le dentiste'], array_column($members, 'title'));
        self::assertSame([78, 67, 55, 51, 43], array_column($members, 'orderScore'));
        self::assertSame(['id', 'title', 'dueDate', 'urgency', 'orderScore'], array_keys($members[0]));
        self::assertSame('2026-10-13', $members[0]['dueDate']);
        self::assertSame('high', $members[0]['urgency']);
        self::assertNull($this->settingsOf('test_user'), 'a preview writes nothing');
    }

    public function testMovingASliderReordersTheSameFiveTasks(): void
    {
        $this->put('{"orderLevels": {"timePressure": 0, "criticality": 4}}');

        $members = $this->preview();

        self::assertSame(['Déclarer les impôts', 'Payer la cantine', 'Appeler le dentiste', 'Rendre le livre', 'Arroser'], array_column($members, 'title'));
        self::assertSame([82, 65, 35, 17, 14], array_column($members, 'orderScore'));
    }
}
