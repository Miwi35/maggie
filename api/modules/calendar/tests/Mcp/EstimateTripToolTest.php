<?php

declare(strict_types=1);

namespace Maggie\Calendar\Tests\Mcp;

use App\Tests\Support\FixtureLoaderTrait;
use App\Tests\Support\SecurityTokenTrait;
use Doctrine\ORM\EntityManagerInterface;
use Maggie\Calendar\Entity\Event;
use Maggie\Calendar\Entity\Place;
use Maggie\Calendar\Mcp\Tool\EstimateTripTool;
use Maggie\Calendar\Mcp\Tool\UpdateEventTool;
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
use Symfony\Contracts\HttpClient\ResponseInterface;

/**
 * estimate_trip (« Partir à l'heure », MAG-653): door to door, by car, transit,
 * on foot or by bicycle. Google is never reached — Routes and Geocoding answer
 * through a MockHttpClient — and the phones are never pushed: the location
 * requester only records who it was asked for.
 *
 * The dentist is at 14:00 in Paris; home is 48.11, -1.68; the dentist's parking
 * 48.119, -1.6672. Routes answers 4 min for a walk, 14 for the drive, 3 for the
 * walk from that parking.
 */
class EstimateTripToolTest extends KernelTestCase
{
    use FixtureLoaderTrait;
    use SecurityTokenTrait;

    private const array DENTIST = [48.118, -1.668];
    private const array PARKING = [48.119, -1.6672];
    private const array HOME = [48.11, -1.68];
    private const array GYM = [48.14, -1.62];
    private const array POOL = [48.1, -1.7];
    private const array POOL_PARKING = [48.1005, -1.7008];

    /** @var list<array{method: string, url: string, body: array<string, mixed>}> */
    private array $requests = [];

    /** @var \ArrayObject<int, User> the users the phones were asked a position of */
    private \ArrayObject $locationRequests;

    private ?EstimateTripTool $tool = null;

    /** @var (callable(array<string, mixed>): ResponseInterface)|null */
    private $routes;

    private string $apiKey = 'test-key';

    protected function setUp(): void
    {
        self::bootKernel();
        Clock::set(new MockClock(new \DateTimeImmutable('2026-11-03 10:00:00', new \DateTimeZone('UTC'))));
        $this->loadFixtures('EstimateTripToolTest.yaml');
        $this->loginFixtureUser();
        $this->requests = [];
        $this->locationRequests = new \ArrayObject();
        $this->tool = null;
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

    /** @param array<string, mixed> $body */
    private function defaultRoute(array $body): ResponseInterface
    {
        $from = $body['origin']['location']['latLng'];

        return match ($body['travelMode']) {
            'DRIVE' => self::json(['routes' => [['duration' => '840s']]]),
            'WALK' => self::json(['routes' => [['duration' => self::PARKING === [$from['latitude'], $from['longitude']] ? '180s' : '240s']]]),
            'BICYCLE' => self::json(['routes' => [['duration' => '600s']]]),
            'TRANSIT' => self::json(['routes' => [[
                'duration' => '1500s',
                'legs' => [['steps' => [
                    ['travelMode' => 'WALK', 'staticDuration' => '120s'],
                    ['travelMode' => 'WALK', 'staticDuration' => '60s'],
                    ['travelMode' => 'TRANSIT', 'staticDuration' => '720s', 'transitDetails' => ['transitLine' => ['name' => 'Ligne a', 'nameShort' => 'a']]],
                    ['travelMode' => 'WALK', 'staticDuration' => '300s'],
                ]]],
            ]]]),
        };
    }

    /** Built once per test, at its first call: the fakes are set before anything uses them. */
    private function tool(): EstimateTripTool
    {
        if (null !== $this->tool) {
            return $this->tool;
        }

        $http = new MockHttpClient(function (string $method, string $url, array $options): ResponseInterface {
            $body = isset($options['body']) && \is_string($options['body']) ? json_decode($options['body'], true) ?? [] : [];
            $this->requests[] = ['method' => $method, 'url' => $url, 'body' => $body];

            if (str_contains($url, 'geocode')) {
                $address = urldecode($url);
                if (str_contains($address, 'Muscle')) {
                    return self::json(['status' => 'OK', 'results' => [['geometry' => ['location' => ['lat' => self::GYM[0], 'lng' => self::GYM[1]]]]]]);
                }
                if (str_contains($address, 'Plongeon')) {
                    return self::json(['status' => 'OK', 'results' => [['geometry' => ['location' => str_contains($address, 'Parking')
                        ? ['lat' => self::POOL_PARKING[0], 'lng' => self::POOL_PARKING[1]]
                        : ['lat' => self::POOL[0], 'lng' => self::POOL[1]]]]]]);
                }

                return str_contains($address, 'Introuvable')
                    ? self::json(['status' => 'ZERO_RESULTS', 'results' => []])
                    : self::json(['status' => 'OK', 'results' => [['geometry' => ['location' => ['lat' => self::DENTIST[0], 'lng' => self::DENTIST[1]]]]]]);
            }

            return null !== $this->routes ? ($this->routes)($body) : $this->defaultRoute($body);
        });

        $container = self::getContainer();
        $container->set(GeocodingClient::class, new GeocodingClient($http, 'http://geocoding.test/maps/api/geocode/json', $this->apiKey));
        $container->set(RoutingClient::class, new RoutingClient($http, 'http://routes.test/directions/v2:computeRoutes', $this->apiKey));
        $container->set(LocationRequester::class, new class($this->locationRequests) extends LocationRequester {
            /** @param \ArrayObject<int, User> $recorded */
            public function __construct(private readonly \ArrayObject $recorded)
            {
            }

            public function request(User $user): int
            {
                $this->recorded[] = $user;

                return 1;
            }
        });

        return $this->tool = $container->get(EstimateTripTool::class);
    }

    /**
     * @param list<string> $modes
     *
     * @return array<string, mixed>
     */
    private function estimate(string $eventRef, array $modes = ['drive'], ?string $origin = null): array
    {
        return $this->call($this->id($eventRef), $modes, $origin);
    }

    /**
     * @param list<mixed> $modes
     *
     * @return array<string, mixed>
     */
    private function call(string $eventId, array $modes, ?string $origin = null): array
    {
        return json_decode(($this->tool())($eventId, $modes, $origin), true, 512, JSON_THROW_ON_ERROR);
    }

    /** Where the phone was, $minutesAgo before the clock, and where the car is parked. */
    private function at(array $position, int $minutesAgo = 2, ?array $car = null): void
    {
        $capturedAt = Clock::get()->now()->modify("-{$minutesAgo} minutes");
        self::getContainer()->get(UserLocations::class)->save($this->id('test_user'), function ($location) use ($position, $capturedAt, $car): void {
            $location->moveTo($position[0], $position[1], 10.0, $capturedAt);
            if (null !== $car) {
                $location->parkCar($car[0], $car[1], $capturedAt);
            }
        });
    }

    /** @return list<string> the travel modes Routes was asked for, in order */
    private function routedModes(): array
    {
        return array_values(array_map(
            static fn (array $request): string => $request['body']['travelMode'],
            array_filter($this->requests, static fn (array $request): bool => str_contains($request['url'], 'routes')),
        ));
    }

    private function geocodingRequests(): int
    {
        return \count(array_filter($this->requests, static fn (array $request): bool => str_contains($request['url'], 'geocode')));
    }

    /** @return list<array<string, mixed>> */
    private static function steps(array $estimate): array
    {
        return $estimate['steps'];
    }

    public function testWithoutAUserItRefusesAndCallsNothing(): void
    {
        self::getContainer()->get('security.token_storage')->setToken(null);

        $result = $this->estimate('dentist');

        self::assertSame(MissingMcpUserException::MESSAGE, $result['error']);
        self::assertSame([], $this->requests);
    }

    public function testByCarWithTheCarAwayAndAKnownParkingWalksDrivesToTheParkingAndWalks(): void
    {
        // 300 m north of home.
        $this->at(self::HOME, car: [48.1127, -1.68]);

        $result = $this->estimate('dentist');

        self::assertArrayNotHasKey('error', $result, json_encode($result, JSON_THROW_ON_ERROR));
        self::assertSame($this->id('dentist'), $result['eventId']);
        self::assertFalse($result['locationRequested']);
        self::assertCount(1, $result['estimates']);
        $drive = $result['estimates'][0];
        self::assertSame('drive', $drive['mode']);
        self::assertSame([
            ['kind' => 'walk', 'minutes' => 4],
            ['kind' => 'drive', 'minutes' => 14],
            ['kind' => 'walk', 'minutes' => 3],
        ], self::steps($drive));
        self::assertSame(21, $drive['durationMinutes']);
        // 14:00 − 21 − 5.
        self::assertSame('2026-11-03T13:34:00+01:00', $drive['departAt']);
        self::assertTrue($drive['parkingKnown']);
        self::assertSame('https://waze.com/ul?ll=48.119,-1.6672&navigate=yes', $drive['navigationUrl']);
        self::assertSame([48.1127, -1.68], [$drive['carLatitude'], $drive['carLongitude']]);
        self::assertSame(['kind' => 'position', 'id' => null, 'label' => 'ma position', 'latitude' => 48.11, 'longitude' => -1.68], array_diff_key($drive['origin'], ['capturedAt' => true]));
        self::assertSame(['WALK', 'DRIVE', 'WALK'], $this->routedModes());
    }

    public function testTheDriveAsksGoogleForTheTrafficAndTheParking(): void
    {
        $this->at(self::HOME, car: [48.1127, -1.68]);

        $this->estimate('dentist');

        $drive = array_values(array_filter($this->requests, static fn (array $r): bool => 'DRIVE' === ($r['body']['travelMode'] ?? null)))[0];
        self::assertSame('POST', $drive['method']);
        self::assertSame('TRAFFIC_AWARE', $drive['body']['routingPreference']);
        self::assertSame(['latitude' => 48.1127, 'longitude' => -1.68], $drive['body']['origin']['location']['latLng']);
        self::assertSame(['latitude' => 48.119, 'longitude' => -1.6672], $drive['body']['destination']['location']['latLng']);
        // 30 minutes before the 13:00 UTC start.
        self::assertSame('2026-11-03T12:30:00Z', $drive['body']['departureTime']);
    }

    public function testACarWithin200MetresNeedsNoWalkToIt(): void
    {
        // 100 m north of home.
        $this->at(self::HOME, car: [48.1109, -1.68]);

        $drive = $this->estimate('dentist')['estimates'][0];

        self::assertSame(['drive', 'walk'], array_column(self::steps($drive), 'kind'));
        self::assertSame(['DRIVE', 'WALK'], $this->routedModes());
    }

    public function testACarNeverSeenParkedIsTakenAtHome(): void
    {
        // 500 m west of home, the car never reported.
        $this->at([48.11, -1.6867]);

        $drive = $this->estimate('dentist')['estimates'][0];

        self::assertSame(['walk', 'drive', 'walk'], array_column(self::steps($drive), 'kind'));
        self::assertSame(self::HOME, [$drive['carLatitude'], $drive['carLongitude']]);
    }

    public function testWithoutAKnownParkingTheDriveEndsAtTheEventPlusFiveMinutes(): void
    {
        $this->at(self::HOME, car: self::HOME);

        $drive = $this->estimate('office')['estimates'][0];

        self::assertSame([['kind' => 'drive', 'minutes' => 19, 'label' => 'dont 5 min pour se garer']], self::steps($drive));
        self::assertFalse($drive['parkingKnown']);
        self::assertSame('https://waze.com/ul?ll=48.13,-1.65&navigate=yes', $drive['navigationUrl']);
        self::assertSame('2026-11-03T13:36:00+01:00', $drive['departAt']);
    }

    public function testADestinationSavedWithoutCoordinatesIsGeocodedAndItsParkingFound(): void
    {
        $this->at(self::HOME, car: self::HOME);

        $drive = $this->estimate('swim')['estimates'][0];

        self::assertTrue($drive['parkingKnown']);
        self::assertSame(['drive', 'walk'], array_column(self::steps($drive), 'kind'));
        self::assertSame('https://waze.com/ul?ll=48.1005,-1.7008&navigate=yes', $drive['navigationUrl']);
        $this->em()->clear();
        $pool = $this->em()->find(Place::class, $this->id('pool'));
        self::assertSame(self::POOL, [$pool->getLatitude(), $pool->getLongitude()]);
        self::assertSame(self::POOL_PARKING, [$pool->getParkingLatitude(), $pool->getParkingLongitude()]);
    }

    public function testByTransitTheStepsAreGooglesWithTheirWalks(): void
    {
        $this->at(self::HOME);

        $transit = $this->estimate('dentist', ['transit'])['estimates'][0];

        self::assertSame([
            ['kind' => 'walk', 'minutes' => 3],
            ['kind' => 'transit', 'minutes' => 12, 'label' => 'a'],
            ['kind' => 'walk', 'minutes' => 5],
        ], self::steps($transit));
        // The whole route, waits included.
        self::assertSame(25, $transit['durationMinutes']);
        self::assertSame('2026-11-03T13:30:00+01:00', $transit['departAt']);
        self::assertSame('https://www.google.com/maps/dir/?api=1&destination=48.118,-1.668&travelmode=transit', $transit['navigationUrl']);
        self::assertFalse($transit['parkingKnown']);
        self::assertArrayNotHasKey('carLatitude', $transit);
        $request = $this->requests[array_key_last($this->requests)]['body'];
        self::assertSame('2026-11-03T12:55:00Z', $request['arrivalTime']);
    }

    public function testSeveralModesGiveOneEstimateEachInOneCall(): void
    {
        $this->at(self::HOME, car: self::HOME);

        $result = $this->estimate('dentist', ['drive', 'transit', 'walk', 'bicycle', 'walk']);

        self::assertSame(['drive', 'transit', 'walk', 'bicycle'], array_column($result['estimates'], 'mode'));
        $walk = $result['estimates'][2];
        self::assertSame([['kind' => 'walk', 'minutes' => 4]], self::steps($walk));
        self::assertSame('https://www.google.com/maps/dir/?api=1&destination=48.118,-1.668&travelmode=walking', $walk['navigationUrl']);
        self::assertSame([['kind' => 'bicycle', 'minutes' => 10]], self::steps($result['estimates'][3]));
        self::assertStringEndsWith('travelmode=bicycling', $result['estimates'][3]['navigationUrl']);
    }

    public function testAPositionOlderThanTenMinutesAsksThePhoneOnceAndUsesTheOldOne(): void
    {
        $this->at(self::HOME, minutesAgo: 11);

        $result = $this->estimate('dentist', ['walk', 'transit']);

        self::assertTrue($result['locationRequested']);
        self::assertCount(1, $this->locationRequests);
        self::assertSame($this->id('test_user'), (string) $this->locationRequests[0]->getId());
        self::assertSame('2026-11-03T09:49:00+00:00', $result['estimates'][0]['origin']['capturedAt']);
        self::assertSame(48.11, $result['estimates'][0]['origin']['latitude']);
    }

    /** MAG-666, recette of 28 Oct. at 22:44: the GPS point at the car was 5 h old and stays the origin; the phone is asked for a new one. */
    public function testAGpsPositionStaysTheOriginWhateverItsAge(): void
    {
        $this->at(self::HOME, minutesAgo: 300);

        $result = $this->estimate('dentist', ['walk']);

        $origin = $result['estimates'][0]['origin'];
        self::assertSame(self::HOME, [$origin['latitude'], $origin['longitude']]);
        self::assertArrayNotHasKey('source', $origin);
        self::assertTrue($result['locationRequested']);
    }

    public function testAFreshPositionAsksNothing(): void
    {
        $this->at(self::HOME, minutesAgo: 9);

        $result = $this->estimate('dentist', ['walk']);

        self::assertFalse($result['locationRequested']);
        self::assertCount(0, $this->locationRequests);
    }

    public function testFromHomeTheOriginIsLaMaison(): void
    {
        $origin = $this->estimate('dentist', ['walk'], 'place:'.$this->id('home'))['estimates'][0]['origin'];

        self::assertSame(['kind' => 'place', 'id' => $this->id('home'), 'label' => 'la maison', 'latitude' => 48.11, 'longitude' => -1.68], $origin);
        self::assertCount(0, $this->locationRequests);
    }

    public function testAPlaceWithoutCoordinatesIsGeocodedAndKept(): void
    {
        $origin = $this->estimate('office', ['walk'], 'place:'.$this->id('gym'))['estimates'][0]['origin'];

        self::assertSame('Salle de sport', $origin['label']);
        $this->em()->clear();
        $gym = $this->em()->find(Place::class, $this->id('gym'));
        self::assertSame(self::GYM, [$gym->getLatitude(), $gym->getLongitude()]);
    }

    public function testFromAnEarlierEventTheOriginIsItsTitle(): void
    {
        $origin = $this->estimate('dentist', ['walk'], 'event:'.$this->id('office'))['estimates'][0]['origin'];

        self::assertSame(['kind' => 'event', 'id' => $this->id('office'), 'label' => 'Réunion au bureau', 'latitude' => 48.13, 'longitude' => -1.65], $origin);
    }

    public function testTheLocationIsGeocodedOnceAndKeptOnTheEvent(): void
    {
        $this->at(self::HOME);

        $this->estimate('dentist', ['walk']);
        self::assertSame(1, $this->geocodingRequests());
        $this->em()->clear();
        $event = $this->em()->find(Event::class, $this->id('dentist'));
        self::assertSame(self::DENTIST, [$event->getLocationLat(), $event->getLocationLon()]);

        $this->requests = [];
        $this->estimate('dentist', ['walk']);
        self::assertSame(0, $this->geocodingRequests());
    }

    public function testANewLocationClearsTheCoordinates(): void
    {
        $result = json_decode(self::getContainer()->get(UpdateEventTool::class)(id: $this->id('office'), location: '1 rue Nouvelle Fictive, 35000 Rennes'), true, 512, JSON_THROW_ON_ERROR);
        self::assertArrayNotHasKey('error', $result, json_encode($result, JSON_THROW_ON_ERROR));

        $this->em()->clear();
        $event = $this->em()->find(Event::class, $this->id('office'));
        self::assertSame('1 rue Nouvelle Fictive, 35000 Rennes', $event->getLocation());
        self::assertNull($event->getLocationLat());
        self::assertNull($event->getLocationLon());
    }

    public function testTheSameLocationKeepsTheCoordinates(): void
    {
        $event = $this->em()->find(Event::class, $this->id('office'));
        $event->setLocation('40 boulevard du Travail Fictif, 35000 Rennes');

        self::assertSame([48.13, -1.65], [$event->getLocationLat(), $event->getLocationLon()]);
    }

    /** @return iterable<string, array{0: callable(self): array{string, list<mixed>, ?string}, 1: string}> */
    public static function refusals(): iterable
    {
        yield 'unknown event' => [static fn (self $t): array => ['01J0000000000000000000000A', ['drive'], null], 'eventId'];
        yield 'not an id' => [static fn (self $t): array => ['dentiste', ['drive'], null], 'eventId'];
        yield "another user's event" => [static fn (self $t): array => [$t->id('other_dentist'), ['drive'], null], 'eventId'];
        yield 'event without location' => [static fn (self $t): array => [$t->id('nowhere'), ['drive'], null], 'eventId'];
        yield 'all-day event' => [static fn (self $t): array => [$t->id('holiday'), ['drive'], null], 'eventId'];
        yield 'location Google cannot find' => [static fn (self $t): array => [$t->id('lost'), ['drive'], null], 'eventId'];
        yield 'unknown mode' => [static fn (self $t): array => [$t->id('dentist'), ['drive', 'teleport'], null], 'modes'];
        yield 'mode not a string' => [static fn (self $t): array => [$t->id('dentist'), [3], null], 'modes'];
        yield 'no mode' => [static fn (self $t): array => [$t->id('dentist'), [], null], 'modes'];
        yield 'malformed origin' => [static fn (self $t): array => [$t->id('dentist'), ['drive'], 'maison'], 'origin'];
        yield 'origin kind unknown' => [static fn (self $t): array => [$t->id('dentist'), ['drive'], 'city:'.$t->id('home')], 'origin'];
        yield 'origin place not an id' => [static fn (self $t): array => [$t->id('dentist'), ['drive'], 'place:maison'], 'origin'];
        yield 'unknown origin place' => [static fn (self $t): array => [$t->id('dentist'), ['drive'], 'place:01J0000000000000000000000A'], 'origin'];
        yield "another user's origin event" => [static fn (self $t): array => [$t->id('dentist'), ['drive'], 'event:'.$t->id('other_dentist')], 'origin'];
        yield 'no position known' => [static fn (self $t): array => [$t->id('dentist'), ['drive'], 'position'], 'origin'];
    }

    /** @param callable(self): array{string, list<mixed>, ?string} $arguments */
    #[DataProvider('refusals')]
    public function testARefusedCallNamesTheFieldAndRoutesNothing(callable $arguments, string $field): void
    {
        [$eventId, $modes, $origin] = $arguments($this);

        $result = $this->call($eventId, $modes, $origin);

        self::assertSame($field, $result['field'] ?? null, json_encode($result, JSON_THROW_ON_ERROR));
        self::assertStringStartsWith("{$field}: ", $result['error']);
        self::assertSame([], $this->routedModes());
        self::assertCount(0, $this->locationRequests);
    }

    public function testWithoutAGoogleKeyTheToolAnswersAnErrorAndCallsNothing(): void
    {
        $this->apiKey = '';
        $this->at(self::HOME);

        $result = $this->estimate('dentist');

        self::assertArrayNotHasKey('field', $result);
        self::assertStringContainsString('GOOGLE_MAPS_API_KEY', $result['error']);
        self::assertSame([], $this->requests);
    }

    public function testRoutesInErrorAnswersAnErrorNotACrash(): void
    {
        $this->routes = static fn (array $body): ResponseInterface => self::json(['error' => ['code' => 500, 'message' => 'boom']], 500);
        $this->at(self::HOME);

        $result = $this->estimate('dentist', ['walk']);

        self::assertArrayNotHasKey('field', $result);
        self::assertStringStartsWith('The trip cannot be estimated: Google Routes', $result['error']);
    }

    public function testNoRouteFoundForTheOnlyModeAnswersAnError(): void
    {
        $this->routes = static fn (array $body): ResponseInterface => self::json([]);
        $this->at(self::HOME);

        $result = $this->estimate('dentist', ['transit']);

        self::assertArrayNotHasKey('field', $result);
        self::assertArrayNotHasKey('estimates', $result);
        self::assertStringStartsWith('The trip cannot be estimated: ', $result['error']);
        self::assertStringContainsString('found no transit route', $result['error']);
    }

    public function testAModeWithoutARouteIsUnavailableAndTheOthersAreKept(): void
    {
        $this->routes = fn (array $body): ResponseInterface => 'TRANSIT' === $body['travelMode'] ? self::json([]) : $this->defaultRoute($body);
        $this->at(self::HOME, car: self::HOME);

        $result = $this->estimate('dentist', ['drive', 'transit', 'bicycle']);

        self::assertArrayNotHasKey('error', $result, json_encode($result, JSON_THROW_ON_ERROR));
        self::assertSame(['drive', 'bicycle'], array_column($result['estimates'], 'mode'));
        self::assertCount(1, $result['unavailable']);
        self::assertSame('transit', $result['unavailable'][0]['mode']);
        self::assertStringContainsString('found no transit route', $result['unavailable'][0]['reason']);
        self::assertFalse($result['locationRequested']);
    }

    public function testEveryModeComputedLeavesNothingUnavailable(): void
    {
        $this->at(self::HOME);

        $result = $this->estimate('dentist', ['walk', 'bicycle']);

        self::assertSame([], $result['unavailable']);
    }

    public function testEveryModeWithoutARouteAnswersAnErrorNamingThem(): void
    {
        $this->routes = static fn (array $body): ResponseInterface => self::json([]);
        $this->at(self::HOME, car: self::HOME);

        $result = $this->estimate('dentist', ['transit', 'walk']);

        self::assertArrayNotHasKey('estimates', $result);
        self::assertArrayNotHasKey('field', $result);
        self::assertStringStartsWith('The trip cannot be estimated: ', $result['error']);
        self::assertStringContainsString('found no transit route', $result['error']);
        self::assertStringContainsString('found no walk route', $result['error']);
    }

    public function testGoogleDownWhileAnotherModeHasNoRouteStillAnswersAnError(): void
    {
        $this->routes = static fn (array $body): ResponseInterface => 'TRANSIT' === $body['travelMode']
            ? self::json([])
            : self::json(['error' => ['code' => 500, 'message' => 'boom']], 500);
        $this->at(self::HOME);

        $result = $this->estimate('dentist', ['transit', 'walk']);

        self::assertArrayNotHasKey('unavailable', $result);
        self::assertStringStartsWith('The trip cannot be estimated: Google Routes is unreachable', $result['error']);
    }
}
