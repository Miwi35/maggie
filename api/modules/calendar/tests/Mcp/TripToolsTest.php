<?php

declare(strict_types=1);

namespace Maggie\Calendar\Tests\Mcp;

use App\Tests\Support\ElasticsearchAssertionTrait;
use App\Tests\Support\FixtureLoaderTrait;
use App\Tests\Support\MercureAssertionTrait;
use App\Tests\Support\SecurityTokenTrait;
use Doctrine\ORM\EntityManagerInterface;
use Maggie\Calendar\Entity\Event;
use Maggie\Calendar\Entity\Trip;
use Maggie\Calendar\Enum\TripDecider;
use Maggie\Calendar\Enum\TripMode;
use Maggie\Calendar\Mcp\Tool\ListTripsTool;
use Maggie\Calendar\Mcp\Tool\SetTripTool;
use Maggie\Calendar\Message\DeleteEventCommand;
use Maggie\Calendar\Message\SetTripCommand;
use Maggie\Calendar\Routing\GeocodingClient;
use Maggie\Calendar\Routing\RoutingClient;
use Maggie\Core\Entity\User;
use Maggie\Core\Mcp\MissingMcpUserException;
use Maggie\Core\Service\UserLocations;
use Maggie\Notification\Push\LocationRequester;
use PHPUnit\Framework\Attributes\DataProvider;
use Symfony\Bundle\FrameworkBundle\Test\KernelTestCase;
use Symfony\Component\Clock\Clock;
use Symfony\Component\Clock\MockClock;
use Symfony\Component\Clock\NativeClock;
use Symfony\Component\HttpClient\MockHttpClient;
use Symfony\Component\HttpClient\Response\MockResponse;
use Symfony\Component\Messenger\MessageBusInterface;
use Symfony\Contracts\HttpClient\ResponseInterface;

/**
 * Kept trips (« Partir à l'heure », MAG-654): set_trip computes and keeps one
 * per event, list_trips reads a Paris day's. Google answers through a
 * MockHttpClient, as in {@see EstimateTripToolTest} whose fixtures this reuses:
 * the dentist is at 14:00 in Paris, its parking a 3 min walk away, any other
 * walk 4 min and a drive 14.
 */
class TripToolsTest extends KernelTestCase
{
    use FixtureLoaderTrait;
    use SecurityTokenTrait;
    use MercureAssertionTrait;
    use ElasticsearchAssertionTrait;

    private const array DENTIST = [48.118, -1.668];
    private const array PARKING = [48.119, -1.6672];
    private const array HOME = [48.11, -1.68];
    private const array CAR = [48.1127, -1.68];
    private const array ELSEWHERE = [48.2, -1.5];

    private bool $faked = false;

    /** @var (callable(array<string, mixed>): ResponseInterface)|null */
    private $routes;

    protected function setUp(): void
    {
        self::bootKernel();
        Clock::set(new MockClock(new \DateTimeImmutable('2026-11-03 10:00:00', new \DateTimeZone('UTC'))));
        $this->loadFixtures('EstimateTripToolTest.yaml');
        $this->resetMercure();
        $this->resetAsyncTransport();
        $this->loginFixtureUser();
        $this->faked = false;
        $this->routes = null;
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

    /** @param array<string, mixed> $body */
    private static function json(array $body, int $status = 200): MockResponse
    {
        return new MockResponse(json_encode($body, JSON_THROW_ON_ERROR), [
            'http_code' => $status,
            'response_headers' => ['content-type: application/json'],
        ]);
    }

    /** Google and the phones, faked once per test before anything uses them. */
    private function fake(): void
    {
        if ($this->faked) {
            return;
        }
        $this->faked = true;

        $http = new MockHttpClient(function (string $method, string $url, array $options): ResponseInterface {
            if (str_contains($url, 'geocode')) {
                $address = urldecode($url);
                if (str_contains($address, 'Introuvable')) {
                    return self::json(['status' => 'ZERO_RESULTS', 'results' => []]);
                }
                // The places saved without coordinates are far from the dentist: never taken for it.
                $at = str_contains($address, 'Sourire') ? self::DENTIST : self::ELSEWHERE;

                return self::json(['status' => 'OK', 'results' => [['geometry' => ['location' => ['lat' => $at[0], 'lng' => $at[1]]]]]]);
            }

            $body = \is_string($options['body'] ?? null) ? json_decode($options['body'], true) : [];
            if (null !== $this->routes) {
                return ($this->routes)($body);
            }
            $from = $body['origin']['location']['latLng'];

            return match ($body['travelMode']) {
                'DRIVE' => self::json(['routes' => [['duration' => '840s']]]),
                'WALK' => self::json(['routes' => [['duration' => self::PARKING === [$from['latitude'], $from['longitude']] ? '180s' : '240s']]]),
                default => self::json(['routes' => [['duration' => '600s']]]),
            };
        });

        $container = self::getContainer();
        $container->set(GeocodingClient::class, new GeocodingClient($http, 'http://geocoding.test/maps/api/geocode/json', 'test-key'));
        $container->set(RoutingClient::class, new RoutingClient($http, 'http://routes.test/directions/v2:computeRoutes', 'test-key'));
        $container->set(LocationRequester::class, new class extends LocationRequester {
            public function __construct()
            {
            }

            public function request(User $user): int
            {
                return 1;
            }
        });
    }

    /** @return array<string, mixed> */
    private function setTrip(string $eventId, string $mode = 'drive', string $origin = 'position', ?string $decidedBy = null): array
    {
        $this->fake();

        return json_decode((self::getContainer()->get(SetTripTool::class))($eventId, $mode, $origin, $decidedBy), true, 512, JSON_THROW_ON_ERROR);
    }

    /** @return array<string, mixed> */
    private function listTrips(string $date): array
    {
        return json_decode((self::getContainer()->get(ListTripsTool::class))($date), true, 512, JSON_THROW_ON_ERROR);
    }

    private function atHomeWithTheCarAway(): void
    {
        $capturedAt = Clock::get()->now()->modify('-2 minutes');
        self::getContainer()->get(UserLocations::class)->save($this->id('test_user'), static function ($location) use ($capturedAt): void {
            $location->moveTo(self::HOME[0], self::HOME[1], 10.0, $capturedAt);
            $location->parkCar(self::CAR[0], self::CAR[1], $capturedAt);
        });
    }

    /** @return list<Trip> */
    private function storedTrips(): array
    {
        $this->em()->clear();

        return $this->em()->getRepository(Trip::class)->findAll();
    }

    private function moveEvent(string $ref, string $utcStart): void
    {
        $event = $this->em()->find(Event::class, $this->id($ref));
        self::assertNotNull($event);
        $start = new \DateTimeImmutable($utcStart, new \DateTimeZone('UTC'));
        $event->scheduleTimed($start, $start->modify('+1 hour'));
        $this->em()->flush();
    }

    public function testWithoutAUserBothToolsRefuseAndKeepNothing(): void
    {
        self::getContainer()->get('security.token_storage')->setToken(null);

        self::assertSame(MissingMcpUserException::MESSAGE, $this->setTrip($this->id('dentist'))['error']);
        self::assertSame(MissingMcpUserException::MESSAGE, $this->listTrips('2026-11-03')['error']);
        self::assertSame([], $this->storedTrips());
    }

    public function testSetTripKeepsTheComputedTripAndPublishesIt(): void
    {
        $this->atHomeWithTheCarAway();

        $result = $this->setTrip($this->id('dentist'));

        self::assertTrue($result['success'] ?? false, json_encode($result, JSON_THROW_ON_ERROR));
        self::assertSame('2026-11-03T13:34:00+01:00', $result['trip']['departAt']);
        self::assertSame('Dentiste', $result['trip']['event']['summary']);

        $trips = $this->storedTrips();
        self::assertCount(1, $trips);
        $trip = $trips[0];
        self::assertSame($result['trip']['id'], (string) $trip->getId());
        self::assertSame($this->id('dentist'), (string) $trip->getEvent()->getId());
        self::assertSame(TripMode::Drive, $trip->getMode());
        self::assertSame([
            ['kind' => 'walk', 'minutes' => 4],
            ['kind' => 'drive', 'minutes' => 14],
            ['kind' => 'walk', 'minutes' => 3],
        ], $trip->getSteps());
        self::assertSame(21, $trip->getDurationMinutes());
        self::assertEquals(new \DateTimeImmutable('2026-11-03T13:34:00+01:00'), $trip->getDepartAt());
        self::assertSame('https://waze.com/ul?ll=48.119,-1.6672&navigate=yes', $trip->getNavigationUrl());
        self::assertSame(['position', null, 'ma position', 48.11, -1.68], [$trip->getOriginKind(), $trip->getOriginRef(), $trip->getOriginLabel(), $trip->getOriginLatitude(), $trip->getOriginLongitude()]);
        self::assertSame(self::CAR, [$trip->getCarLatitude(), $trip->getCarLongitude()]);
        self::assertSame(TripDecider::Maggie, $trip->getOriginDecidedBy());
        self::assertEquals(new \DateTimeImmutable('2026-11-03 10:00:00 UTC'), $trip->getComputedAt());

        $topic = '/api/trips/'.$trip->getId();
        $this->assertMercurePublishedOnce($topic);
        $payload = $this->mercurePayloadsOn($topic)[0];
        self::assertSame('/api/events/'.$this->id('dentist'), $payload['event']);
        self::assertSame('drive', $payload['mode']);
        self::assertSame('maggie', $payload['originDecidedBy']);
        // To the event's owner, through its agenda.
        $topics = array_merge(...array_map(static fn ($update): array => $update->getTopics(), $this->getMercureHub()->getUpdates()));
        self::assertContains('/users/'.$this->id('test_user').$topic, $topics);
    }

    public function testASecondSetTripReplacesTheTripInPlace(): void
    {
        $this->atHomeWithTheCarAway();
        $first = $this->setTrip($this->id('dentist'));
        Clock::set(new MockClock(new \DateTimeImmutable('2026-11-03 11:00:00', new \DateTimeZone('UTC'))));
        $this->resetMercure();

        $second = $this->setTrip($this->id('dentist'), 'walk', 'place:'.$this->id('home'), 'user');

        self::assertSame($first['trip']['id'], $second['trip']['id']);
        $trips = $this->storedTrips();
        self::assertCount(1, $trips);
        $trip = $trips[0];
        self::assertSame(TripMode::Walk, $trip->getMode());
        self::assertSame([['kind' => 'walk', 'minutes' => 4]], $trip->getSteps());
        self::assertEquals(new \DateTimeImmutable('2026-11-03T13:51:00+01:00'), $trip->getDepartAt());
        self::assertSame(['place', $this->id('home'), 'la maison'], [$trip->getOriginKind(), $trip->getOriginRef(), $trip->getOriginLabel()]);
        self::assertNull($trip->getCarLatitude());
        self::assertSame(TripDecider::User, $trip->getOriginDecidedBy());
        self::assertEquals(new \DateTimeImmutable('2026-11-03 11:00:00 UTC'), $trip->getComputedAt());

        $topic = '/api/trips/'.$trip->getId();
        $this->assertMercurePublishedOnce($topic);
        $payload = $this->mercurePayloadsOn($topic)[0];
        self::assertSame('walk', $payload['mode']);
        self::assertSame('user', $payload['originDecidedBy']);
        self::assertArrayNotHasKey('deleted', $payload);
    }

    public function testDeletingTheEventDeletesItsTripAndTellsTheScreens(): void
    {
        $this->atHomeWithTheCarAway();
        $tripId = $this->setTrip($this->id('dentist'))['trip']['id'];
        $this->resetMercure();
        self::getContainer()->get('security.token_storage')->setToken(null);

        self::getContainer()->get(MessageBusInterface::class)->dispatch(new DeleteEventCommand($this->id('dentist')));

        self::assertSame([], $this->storedTrips());
        $this->assertMercureDeletePublished('/api/trips/'.$tripId);
    }

    /** @return iterable<string, array{0: callable(self): array{string, string, string, ?string}, 1: string}> */
    public static function refusals(): iterable
    {
        yield 'unknown event' => [static fn (self $t): array => ['01J0000000000000000000000A', 'drive', 'position', null], 'eventId'];
        yield 'not an id' => [static fn (self $t): array => ['dentiste', 'drive', 'position', null], 'eventId'];
        yield "another user's event" => [static fn (self $t): array => [$t->id('other_dentist'), 'drive', 'position', null], 'eventId'];
        yield 'event without location' => [static fn (self $t): array => [$t->id('nowhere'), 'drive', 'position', null], 'eventId'];
        yield 'all-day event' => [static fn (self $t): array => [$t->id('holiday'), 'drive', 'position', null], 'eventId'];
        yield 'location Google cannot find' => [static fn (self $t): array => [$t->id('lost'), 'drive', 'position', null], 'eventId'];
        yield 'unknown mode' => [static fn (self $t): array => [$t->id('dentist'), 'teleport', 'position', null], 'mode'];
        yield 'malformed origin' => [static fn (self $t): array => [$t->id('dentist'), 'drive', 'maison', null], 'origin'];
        yield 'unknown origin place' => [static fn (self $t): array => [$t->id('dentist'), 'drive', 'place:01J0000000000000000000000A', null], 'origin'];
        yield 'decidedBy neither user nor maggie' => [static fn (self $t): array => [$t->id('dentist'), 'drive', 'position', 'owner'], 'decidedBy'];
    }

    /** @param callable(self): array{string, string, string, ?string} $arguments */
    #[DataProvider('refusals')]
    public function testARefusedCallNamesTheFieldAndKeepsNothing(callable $arguments, string $field): void
    {
        $this->atHomeWithTheCarAway();
        [$eventId, $mode, $origin, $decidedBy] = $arguments($this);

        $result = $this->setTrip($eventId, $mode, $origin, $decidedBy);

        self::assertSame($field, $result['field'] ?? null, json_encode($result, JSON_THROW_ON_ERROR));
        self::assertStringStartsWith("{$field}: ", $result['error']);
        self::assertSame([], $this->storedTrips());
        self::assertSame([], $this->getMercureHub()->getUpdates());
    }

    public function testARefusalKeepsTheTripTheEventHad(): void
    {
        $this->atHomeWithTheCarAway();
        $this->setTrip($this->id('dentist'));

        $result = $this->setTrip($this->id('dentist'), 'drive', 'place:01J0000000000000000000000A');

        self::assertSame('origin', $result['field']);
        $trips = $this->storedTrips();
        self::assertCount(1, $trips);
        self::assertSame('position', $trips[0]->getOriginKind());
    }

    public function testGoogleFailingAnswersAnErrorAndKeepsNothing(): void
    {
        $this->atHomeWithTheCarAway();
        $this->routes = static fn (array $body): ResponseInterface => self::json(['error' => ['code' => 500, 'message' => 'boom']], 500);

        $result = $this->setTrip($this->id('dentist'), 'walk');

        self::assertArrayNotHasKey('field', $result);
        self::assertStringStartsWith('The trip cannot be computed: ', $result['error']);
        self::assertSame([], $this->storedTrips());
    }

    public function testListTripsReadsTheParisDayByDepartureAndOnlyTheUsers(): void
    {
        $this->atHomeWithTheCarAway();
        // 09:00 in Paris: leaves before the dentist, kept after it.
        $this->moveEvent('office', '2026-11-03 08:00:00');
        // 00:30 in Paris on the 4th, still the 3rd in UTC: leaves on the 4th.
        $this->moveEvent('swim', '2026-11-03 23:30:00');
        $dentist = $this->setTrip($this->id('dentist'))['trip']['id'];
        $office = $this->setTrip($this->id('office'), 'walk', 'place:'.$this->id('home'))['trip']['id'];
        $swim = $this->setTrip($this->id('swim'), 'walk', 'place:'.$this->id('home'))['trip']['id'];
        self::getContainer()->get(MessageBusInterface::class)->dispatch(new SetTripCommand(
            $this->id('other_user'), $this->id('other_dentist'), TripMode::Walk, 'event:'.$this->id('other_dentist'),
        ));
        self::assertCount(4, $this->storedTrips());

        $today = $this->listTrips('2026-11-03');
        self::assertSame('2026-11-03', $today['date']);
        self::assertSame([$office, $dentist], array_column($today['trips'], 'id'));
        self::assertSame('2026-11-03T08:51:00+01:00', $today['trips'][0]['departAt']);
        self::assertSame($this->id('dentist'), $today['trips'][1]['eventId']);
        self::assertSame([$swim], array_column($this->listTrips('2026-11-04')['trips'], 'id'));
        self::assertSame([], $this->listTrips('2026-11-05')['trips']);
    }

    /** @return iterable<string, array{string}> */
    public static function malformedDates(): iterable
    {
        yield 'words' => ['demain'];
        yield 'no such day' => ['2026-02-30'];
        yield 'with a time' => ['2026-11-03T10:00'];
        yield 'empty' => [''];
    }

    #[DataProvider('malformedDates')]
    public function testListTripsRefusesADateThatIsNotADay(string $date): void
    {
        $result = $this->listTrips($date);

        self::assertSame('date', $result['field'] ?? null);
        self::assertStringContainsString('YYYY-MM-DD', $result['error']);
    }
}
