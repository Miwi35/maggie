<?php

namespace Maggie\Calendar\Tests\Api;

use App\Tests\Support\AuthenticatedTestTrait;
use App\Tests\Support\ElasticsearchAssertionTrait;
use App\Tests\Support\FixtureLoaderTrait;
use App\Tests\Support\MercureAssertionTrait;
use Maggie\Calendar\Entity\Event;
use Maggie\Core\Entity\User;
use PHPUnit\Framework\Attributes\DataProvider;
use Symfony\Bundle\FrameworkBundle\KernelBrowser;
use Symfony\Bundle\FrameworkBundle\Test\WebTestCase;

/**
 * An all-day event is a pair of dates, the last one included, and no instant (MAG-382).
 *
 * The owner's report: a birthday on the 1st showed on the 1st and the 2nd on
 * the mobile, because its `00:00Z → 00:00Z` of the next day, read in Paris,
 * ended at 01:00 on the 2nd. A day stored as a date has nothing to read in a
 * zone.
 */
class EventAllDayApiTest extends WebTestCase
{
    use FixtureLoaderTrait;
    use AuthenticatedTestTrait;
    use MercureAssertionTrait;
    use ElasticsearchAssertionTrait;

    private KernelBrowser $client;

    protected function setUp(): void
    {
        $this->client = self::createClient();
        $this->resetMercure();
        $this->resetAsyncTransport();
    }

    private function login(): void
    {
        $this->loadFixtures('EventAllDayApiTest.yaml');
        /** @var User $user */
        $user = $this->getFixture('test_user');
        $this->authenticateAsUser($user);
        $this->resetMercure();
        $this->resetAsyncTransport();
    }

    /**
     * @param array<string, mixed>|null $body
     *
     * @return array<string, mixed>
     */
    private function send(string $method, string $uri, ?array $body = null, bool $authenticated = true): array
    {
        $this->client->request($method, $uri, [], [], array_merge([
            'CONTENT_TYPE' => 'PATCH' === $method ? 'application/merge-patch+json' : 'application/ld+json',
            'HTTP_ACCEPT' => 'application/ld+json',
        ], $authenticated ? $this->authHeaders() : []), null !== $body ? json_encode($body, JSON_THROW_ON_ERROR) : null);

        $content = (string) $this->client->getResponse()->getContent();

        return '' === $content ? [] : json_decode($content, true, 512, JSON_THROW_ON_ERROR);
    }

    private function agendaIri(): string
    {
        return '/api/agendas/'.$this->getFixture('test_agenda')->getId();
    }

    /** @return array<string, mixed> the row as the database holds it */
    private function row(string $id): array
    {
        $em = self::getContainer()->get('doctrine.orm.entity_manager');

        return $em->getConnection()->fetchAssociative(
            'SELECT all_day, start_date, end_date, start_at, end_at FROM event WHERE id = :id',
            ['id' => $em->getRepository(Event::class)->find($id)?->getId()->toRfc4122()],
        ) ?: [];
    }

    public function testCreatingAnAllDayEventStoresDatesAndNoInstant(): void
    {
        $this->login();

        $data = $this->send('POST', '/api/events', [
            'summary' => 'Anniversaire',
            'allDay' => true,
            'startDate' => '2037-01-01',
            'endDate' => '2037-01-02',
            'agenda' => $this->agendaIri(),
        ]);

        self::assertResponseStatusCodeSame(201);
        self::assertSame('2037-01-01', $data['startDate']);
        self::assertSame('2037-01-02', $data['endDate']);
        self::assertNull($data['startAt'] ?? null);
        self::assertNull($data['endAt'] ?? null);

        $row = $this->row($data['id']);
        self::assertTrue($row['all_day']);
        self::assertSame('2037-01-01', $row['start_date']);
        self::assertSame('2037-01-02', $row['end_date']);
        self::assertNull($row['start_at']);
        self::assertNull($row['end_at']);

        $this->assertMercureUpdatePublished('/events/');
        $payload = $this->mercurePayloadsOn('/api/events/'.$data['id'])[0] ?? [];
        self::assertSame('2037-01-01', $payload['startDate'] ?? null);
        self::assertSame('2037-01-02', $payload['endDate'] ?? null);
        self::assertTrue($payload['allDay'] ?? null);
        $this->assertElasticsearchIndexDispatched(Event::class);
    }

    public function testAnAbsentEndMakesItOneDay(): void
    {
        $this->login();

        $data = $this->send('POST', '/api/events', [
            'summary' => 'Férié',
            'allDay' => true,
            'startDate' => '2037-05-01',
            'agenda' => $this->agendaIri(),
        ]);

        self::assertResponseStatusCodeSame(201);
        self::assertSame('2037-05-02', $this->row($data['id'])['end_date']);
    }

    public function testAnAnonymousCreationIsRefused(): void
    {
        $this->login();

        $this->send('POST', '/api/events', [
            'summary' => 'Anniversaire',
            'allDay' => true,
            'startDate' => '2037-01-01',
            'agenda' => $this->agendaIri(),
        ], authenticated: false);

        self::assertResponseStatusCodeSame(401);
    }

    /** @return iterable<string, array{array<string, mixed>, string}> */
    public static function mixedSchedules(): iterable
    {
        yield 'an end before the start' => [['allDay' => true, 'startDate' => '2037-01-28', 'endDate' => '2037-01-26'], 'endDate'];
        // The end is excluded: on the start day, it is a day of nothing.
        yield 'an end on the start' => [['allDay' => true, 'startDate' => '2037-01-28', 'endDate' => '2037-01-28'], 'endDate'];
        yield 'a day with no first day' => [['allDay' => true, 'endDate' => '2037-01-26'], 'startDate'];
        yield 'a day with an instant' => [['allDay' => true, 'startDate' => '2037-01-26', 'startAt' => '2037-01-26T00:00:00Z'], 'startAt'];
        yield 'a timed event with a day' => [['startAt' => '2037-01-26T09:00:00+01:00', 'endAt' => '2037-01-26T10:00:00+01:00', 'startDate' => '2037-01-26'], 'startDate'];
        yield 'a timed event with no end' => [['startAt' => '2037-01-26T09:00:00+01:00'], 'endAt'];
    }

    /** @param array<string, mixed> $schedule */
    #[DataProvider('mixedSchedules')]
    public function testAScheduleThatIsNotOneKindWholeIsRefused(array $schedule, string $path): void
    {
        $this->login();

        $data = $this->send('POST', '/api/events', ['summary' => 'Mélange', 'agenda' => $this->agendaIri()] + $schedule);

        // A violation, as every validation of the API answers it (422).
        self::assertResponseStatusCodeSame(422);
        self::assertContains($path, array_column($data['violations'] ?? [], 'propertyPath'));
        $this->assertMercureUpdateCount(0);
    }

    public function testADayThatDoesNotExistIsRefused(): void
    {
        $this->login();

        $this->send('POST', '/api/events', [
            'summary' => 'Jamais',
            'allDay' => true,
            'startDate' => '2037-02-30',
            'agenda' => $this->agendaIri(),
        ]);

        self::assertResponseStatusCodeSame(400);
    }

    public function testATimedEventBecomesAllDayAndBackThroughPatch(): void
    {
        $this->login();
        $id = (string) $this->getFixture('timed')->getId();

        $this->send('PATCH', '/api/events/'.$id, [
            'allDay' => true,
            'startDate' => '2037-01-26',
            'endDate' => '2037-01-29',
            'startAt' => null,
            'endAt' => null,
        ]);

        self::assertResponseIsSuccessful();
        $row = $this->row($id);
        self::assertSame(['2037-01-26', '2037-01-29'], [$row['start_date'], $row['end_date']]);
        self::assertNull($row['start_at']);
        $this->assertMercureUpdatePublished('/events/');
        $this->assertElasticsearchIndexDispatched(Event::class);

        $this->send('PATCH', '/api/events/'.$id, [
            'allDay' => false,
            'startAt' => '2037-01-26T09:00:00+01:00',
            'endAt' => '2037-01-26T10:00:00+01:00',
            'startDate' => null,
            'endDate' => null,
        ]);

        self::assertResponseIsSuccessful();
        $row = $this->row($id);
        self::assertFalse($row['all_day']);
        self::assertNull($row['start_date']);
        self::assertNull($row['end_date']);
        self::assertNotNull($row['start_at']);
    }

    public function testMovingTheLastDayAloneKeepsTheFirst(): void
    {
        $this->login();
        $id = (string) $this->getFixture('three_days')->getId();

        $this->send('PATCH', '/api/events/'.$id, ['endDate' => '2037-01-31']);

        self::assertResponseIsSuccessful();
        $row = $this->row($id);
        self::assertSame(['2037-01-26', '2037-01-31'], [$row['start_date'], $row['end_date']]);
    }

    public function testSwitchingToAllDayWithoutClearingTheInstantsIsRefused(): void
    {
        $this->login();
        $id = (string) $this->getFixture('timed')->getId();

        $this->send('PATCH', '/api/events/'.$id, ['allDay' => true, 'startDate' => '2037-01-26']);

        // A violation, as every validation of the API answers it (422).
        self::assertResponseStatusCodeSame(422);
        self::assertNull($this->row($id)['start_date']);
    }

    /**
     * The clients ask a range of instants, as before; an all-day event answers
     * by its days. A month in Paris is `[1st 00:00+01:00, 1st of next month 00:00+01:00)`.
     *
     * @return iterable<string, array{array<string, string>, list<string>}>
     */
    public static function ranges(): iterable
    {
        // The owner's case: the 1st is in the day of the 1st, not in the day of the 2nd.
        yield 'the day of the 1st' => [['startAt[before]' => '2037-01-02T00:00:00+01:00', 'endAt[after]' => '2037-01-01T00:00:00+01:00'], ['Anniversaire']];
        yield 'the day of the 2nd' => [['startAt[before]' => '2037-01-03T00:00:00+01:00', 'endAt[after]' => '2037-01-02T00:00:00+01:00'], ['Dentiste']];
        // Read in Paris: midnight of the 2nd in Paris is still the 1st in UTC.
        yield 'the day of the 2nd, its bounds sent in UTC' => [['startAt[before]' => '2037-01-02T23:00:00Z', 'endAt[after]' => '2037-01-01T23:00:00Z'], ['Dentiste']];
        yield 'the 27th, inside the three days' => [['startAt[before]' => '2037-01-28T00:00:00+01:00', 'endAt[after]' => '2037-01-27T00:00:00+01:00'], ['Séminaire']];
        yield 'the 28th, their last day' => [['startAt[before]' => '2037-01-29T00:00:00+01:00', 'endAt[after]' => '2037-01-28T00:00:00+01:00'], ['Séminaire']];
        yield 'the 29th, after them' => [['startAt[before]' => '2037-01-30T00:00:00+01:00', 'endAt[after]' => '2037-01-29T00:00:00+01:00'], []];
        // The mobile's shape: contained in the range.
        yield 'starting from the 26th, ending by the 31st' => [['startAt[after]' => '2037-01-26T00:00:00+01:00', 'endAt[before]' => '2037-02-01T00:00:00+01:00'], ['Séminaire']];
        yield 'the month, as the admin asks it' => [['startAt[after]' => '2037-01-01T00:00:00+01:00', 'startAt[before]' => '2037-02-01T00:00:00+01:00'], ['Anniversaire', 'Dentiste', 'Séminaire']];
    }

    /**
     * @param array<string, string> $query
     * @param list<string>          $expected
     */
    #[DataProvider('ranges')]
    public function testARangeTakesTheAllDayEventsWhoseDaysMeetIt(array $query, array $expected): void
    {
        $this->login();

        $data = $this->send('GET', '/api/events?'.http_build_query($query));

        self::assertResponseIsSuccessful();
        $summaries = array_column($data['member'], 'summary');
        sort($summaries);
        self::assertSame($expected, $summaries);
    }

    public function testAnAllDayEventIsReadAsItsDays(): void
    {
        $this->login();

        $data = $this->send('GET', '/api/events/'.$this->getFixture('three_days')->getId());

        self::assertResponseIsSuccessful();
        self::assertTrue($data['allDay']);
        self::assertSame('2037-01-26', $data['startDate']);
        self::assertSame('2037-01-29', $data['endDate'], 'Excluded, as Google stores it');
        self::assertNull($data['startAt'] ?? null);
    }
}
