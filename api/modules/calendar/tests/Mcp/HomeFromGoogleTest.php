<?php

declare(strict_types=1);

namespace Maggie\Calendar\Tests\Mcp;

use App\Tests\Support\ElasticsearchAssertionTrait;
use App\Tests\Support\FixtureLoaderTrait;
use App\Tests\Support\MercureAssertionTrait;
use App\Tests\Support\SecurityTokenTrait;
use Doctrine\ORM\EntityManagerInterface;
use Maggie\Calendar\Entity\Place;
use Maggie\Calendar\EventSubscriber\ImportHomeOnGoogleSignIn;
use Maggie\Calendar\Google\GooglePeopleClient;
use Maggie\Calendar\Mcp\Tool\EstimateTripTool;
use Maggie\Calendar\Mcp\Tool\ListPlacesTool;
use Maggie\Calendar\Routing\GeocodingClient;
use Maggie\Calendar\Routing\RoutingClient;
use Maggie\Calendar\Service\GoogleCalendarApiClient;
use Maggie\Core\Event\UserSignedInWithGoogle;
use PHPUnit\Framework\Attributes\DataProvider;
use Symfony\Bundle\FrameworkBundle\Test\KernelTestCase;
use Symfony\Component\Clock\Clock;
use Symfony\Component\Clock\MockClock;
use Symfony\Component\Clock\NativeClock;
use Symfony\Component\HttpClient\MockHttpClient;
use Symfony\Component\HttpClient\Response\MockResponse;
use Symfony\Contracts\HttpClient\ResponseInterface;

/**
 * The « Maison » taken from the Google account (MAG-664). Google is never
 * reached: People, Geocoding and Routes answer through one MockHttpClient.
 *
 * Google's « Domicile » is 12 rue des Glycines Imaginaires; Geocoding places
 * every address at 48.11, -1.68.
 */
class HomeFromGoogleTest extends KernelTestCase
{
    use FixtureLoaderTrait;
    use SecurityTokenTrait;
    use MercureAssertionTrait;
    use ElasticsearchAssertionTrait;

    private const string HOME_ADDRESS = '12 rue des Glycines Imaginaires, 35000 Rennes, France';

    /** @var list<array{url: string, options: array<string, mixed>}> */
    private array $peopleRequests = [];

    private int $geocodingRequests = 0;

    /** @var (callable(): ResponseInterface)|null */
    private $people;

    private bool $geocodingFails = false;

    protected function setUp(): void
    {
        self::bootKernel();
        Clock::set(new MockClock(new \DateTimeImmutable('2026-11-03 10:00:00', new \DateTimeZone('UTC'))));
        $this->loadFixtures('HomeFromGoogleTest.yaml');
        $this->loginFixtureUser();
        $this->resetMercure();
        $this->resetAsyncTransport();
        $this->peopleRequests = [];
        $this->geocodingRequests = 0;
        $this->people = fn (): ResponseInterface => self::homeAddresses();
        $this->geocodingFails = false;

        $http = new MockHttpClient(function (string $method, string $url, array $options): ResponseInterface {
            if (str_contains($url, 'geocode')) {
                ++$this->geocodingRequests;

                return $this->geocodingFails
                    ? self::json(['status' => 'OVER_QUERY_LIMIT', 'results' => []])
                    : self::json(['status' => 'OK', 'results' => [['geometry' => ['location' => ['lat' => 48.11, 'lng' => -1.68]]]]]);
            }
            if (str_contains($url, 'routes')) {
                return self::json(['routes' => [['duration' => '600s']]]);
            }

            $this->peopleRequests[] = ['url' => $url, 'options' => $options];

            return ($this->people)();
        });

        $container = self::getContainer();
        $container->set(GooglePeopleClient::class, new GooglePeopleClient($http, $container->get(GoogleCalendarApiClient::class), 'http://google.test/'));
        $container->set(GeocodingClient::class, new GeocodingClient($http, 'http://geocoding.test/maps/api/geocode/json', 'test-key'));
        $container->set(RoutingClient::class, new RoutingClient($http, 'http://routes.test/directions/v2:computeRoutes', 'test-key'));
    }

    protected function tearDown(): void
    {
        Clock::set(new NativeClock());
        parent::tearDown();
    }

    /** @param array<string, mixed> $body */
    private static function json(array $body, int $status = 200): MockResponse
    {
        return new MockResponse(json_encode($body, JSON_THROW_ON_ERROR), [
            'http_code' => $status,
            'response_headers' => ['content-type: application/json'],
        ]);
    }

    private static function homeAddresses(): MockResponse
    {
        return self::json(['addresses' => [
            ['type' => 'work', 'formattedValue' => '40 boulevard du Travail Fictif, 35000 Rennes', 'metadata' => ['primary' => true]],
            ['type' => 'home', 'formattedValue' => self::HOME_ADDRESS],
        ]]);
    }

    private function em(): EntityManagerInterface
    {
        return self::getContainer()->get('doctrine.orm.entity_manager');
    }

    private function id(string $ref): string
    {
        return (string) $this->getFixture($ref)->getId();
    }

    /** @return list<Place> the places of the user, as stored */
    private function placesOf(string $ref): array
    {
        $this->em()->clear();

        return $this->em()->getRepository(Place::class)->findBy(['user' => $this->id($ref)]);
    }

    /** @return array<string, mixed> */
    private function list(): array
    {
        return json_decode((self::getContainer()->get(ListPlacesTool::class))(), true, 512, JSON_THROW_ON_ERROR);
    }

    public function testSignInTakesTheHomeAddressOfTheAccountAndLocatesIt(): void
    {
        (self::getContainer()->get(ImportHomeOnGoogleSignIn::class))(new UserSignedInWithGoogle($this->id('test_user')));

        $places = $this->placesOf('test_user');
        self::assertCount(1, $places);
        self::assertSame('Maison', $places[0]->getName());
        self::assertTrue($places[0]->isHome());
        self::assertSame(self::HOME_ADDRESS, $places[0]->getAddress());
        self::assertSame([48.11, -1.68], [$places[0]->getLatitude(), $places[0]->getLongitude()]);
        $this->assertMercureUpdateCount(1);
    }

    public function testPeopleIsAskedForTheAddressesWithTheAccessTokenOfTheUser(): void
    {
        $this->list();

        self::assertCount(1, $this->peopleRequests);
        self::assertStringStartsWith('http://google.test/v1/people/me', $this->peopleRequests[0]['url']);
        self::assertStringContainsString('personFields=addresses', $this->peopleRequests[0]['url']);
        self::assertContains('Authorization: Bearer fixture-access-token', $this->peopleRequests[0]['options']['headers']);
    }

    public function testTheHomeAddressIsPreferredToThePrimaryOne(): void
    {
        $data = $this->list();

        self::assertCount(1, $data['places']);
        self::assertSame(self::HOME_ADDRESS, $data['places'][0]['address']);
    }

    public function testWithoutAHomeTypedAddressTheFirstPrimaryOneIsTaken(): void
    {
        $this->people = static fn (): ResponseInterface => self::json(['addresses' => [
            ['type' => 'other', 'formattedValue' => '1 rue Secondaire Fictive, 35000 Rennes'],
            ['type' => 'work', 'formattedValue' => '40 boulevard du Travail Fictif, 35000 Rennes', 'metadata' => ['primary' => true]],
        ]]);

        self::assertSame('40 boulevard du Travail Fictif, 35000 Rennes', $this->list()['places'][0]['address']);
    }

    public function testAnAddressWithoutFormattedValueIsAssembledFromItsParts(): void
    {
        $this->people = static fn (): ResponseInterface => self::json(['addresses' => [
            ['type' => 'home', 'streetAddress' => '12 rue des Glycines Imaginaires', 'postalCode' => '35000', 'city' => 'Rennes'],
        ]]);

        self::assertSame('12 rue des Glycines Imaginaires, 35000, Rennes', $this->list()['places'][0]['address']);
    }

    public function testAListPlacesWithAHomeNeverAsksGoogleAndChangesNothing(): void
    {
        $this->loginFixtureUser('other_user');

        $data = $this->list();

        self::assertCount(0, $this->peopleRequests);
        self::assertSame(['Chez moi'], array_column($data['places'], 'name'));
        self::assertSame('3 impasse du Voisin Fictif, 35000 Rennes', $this->placesOf('other_user')[0]->getAddress());
        $this->assertMercureUpdateCount(0);
    }

    public function testSignInOnAUserWithAHomeAsksGoogleNothing(): void
    {
        (self::getContainer()->get(ImportHomeOnGoogleSignIn::class))(new UserSignedInWithGoogle($this->id('other_user')));

        self::assertCount(0, $this->peopleRequests);
        self::assertCount(1, $this->placesOf('other_user'));
    }

    public function testASignInWithoutGoogleTokensAsksGoogleNothing(): void
    {
        (self::getContainer()->get(ImportHomeOnGoogleSignIn::class))(new UserSignedInWithGoogle($this->id('no_google_user')));

        self::assertCount(0, $this->peopleRequests);
        self::assertCount(0, $this->placesOf('no_google_user'));
    }

    /** @return iterable<string, array{callable(): ResponseInterface}> */
    public static function failures(): iterable
    {
        yield 'no address at all' => [static fn (): ResponseInterface => self::json(['resourceName' => 'people/1'])];
        yield 'an empty address list' => [static fn (): ResponseInterface => self::json(['addresses' => []])];
        yield 'an address without text' => [static fn (): ResponseInterface => self::json(['addresses' => [['type' => 'home']]])];
        yield 'the scope refused' => [static fn (): ResponseInterface => self::json(['error' => ['code' => 403, 'status' => 'PERMISSION_DENIED']], 403)];
        yield 'Google down' => [static fn (): ResponseInterface => self::json(['error' => ['code' => 503]], 503)];
        yield 'an unreadable answer' => [static fn (): ResponseInterface => new MockResponse('<html>', ['http_code' => 200])];
    }

    /** @param callable(): ResponseInterface $answer */
    #[DataProvider('failures')]
    public function testWithoutAnUsableAddressThereIsNoHomeAndNoError(callable $answer): void
    {
        $this->people = $answer;

        self::assertSame(['places' => []], $this->list());
        self::assertCount(0, $this->placesOf('test_user'));
        $this->assertMercureUpdateCount(0);
    }

    public function testSignInWithTheScopeRefusedLeavesTheSignInAlone(): void
    {
        $this->people = static fn (): ResponseInterface => self::json(['error' => ['code' => 403]], 403);

        (self::getContainer()->get(ImportHomeOnGoogleSignIn::class))(new UserSignedInWithGoogle($this->id('test_user')));

        self::assertCount(1, $this->peopleRequests);
        self::assertCount(0, $this->placesOf('test_user'));
    }

    public function testAnAddressGoogleCannotLocateIsKeptWithoutCoordinates(): void
    {
        $this->geocodingFails = true;

        $place = $this->list()['places'][0];

        self::assertSame(self::HOME_ADDRESS, $place['address']);
        self::assertNull($place['latitude']);
        self::assertTrue($place['isHome']);
    }

    public function testNoHomeIsAskedAgainWithinADayEvenWhenNothingWasFound(): void
    {
        $this->people = static fn (): ResponseInterface => self::json([]);

        $this->list();
        $this->list();
        $this->list();

        self::assertCount(1, $this->peopleRequests);
    }

    public function testASignInTriesAgainWhateverTheLastAttempt(): void
    {
        $this->people = static fn (): ResponseInterface => self::json([]);
        $this->list();
        $this->people = fn (): ResponseInterface => self::homeAddresses();

        (self::getContainer()->get(ImportHomeOnGoogleSignIn::class))(new UserSignedInWithGoogle($this->id('test_user')));

        self::assertCount(2, $this->peopleRequests);
        self::assertCount(1, $this->placesOf('test_user'));
    }

    public function testASignInIsNotRepeatedByTheNextListPlaces(): void
    {
        $this->people = static fn (): ResponseInterface => self::json([]);
        (self::getContainer()->get(ImportHomeOnGoogleSignIn::class))(new UserSignedInWithGoogle($this->id('test_user')));

        $this->list();

        self::assertCount(1, $this->peopleRequests);
    }

    public function testTheImportedHomeIsNeverReplacedByGoogle(): void
    {
        $home = $this->list()['places'][0];
        $this->people = static fn (): ResponseInterface => self::json(['addresses' => [['type' => 'home', 'formattedValue' => '99 rue du Déménagement, 75001 Paris']]]);

        (self::getContainer()->get(ImportHomeOnGoogleSignIn::class))(new UserSignedInWithGoogle($this->id('test_user')));

        $places = $this->placesOf('test_user');
        self::assertCount(1, $places);
        self::assertSame($home['id'], (string) $places[0]->getId());
        self::assertSame(self::HOME_ADDRESS, $places[0]->getAddress());
    }

    public function testEstimateTripFromHomeImportsTheHomeOnce(): void
    {
        $tool = self::getContainer()->get(EstimateTripTool::class);

        $estimate = json_decode($tool($this->id('dentist'), ['walk'], 'home'), true, 512, JSON_THROW_ON_ERROR);

        self::assertSame('la maison', $estimate['estimates'][0]['origin']['label']);
        self::assertSame('place', $estimate['estimates'][0]['origin']['kind']);
        self::assertCount(1, $this->peopleRequests);
        self::assertCount(1, $this->placesOf('test_user'));
    }

    public function testEstimateTripFromHomeWithoutAnyHomeAsksToAskTheOwnerAndTriesGoogleOnce(): void
    {
        $this->people = static fn (): ResponseInterface => self::json([]);
        $tool = self::getContainer()->get(EstimateTripTool::class);

        $first = json_decode($tool($this->id('dentist'), ['walk'], 'home'), true, 512, JSON_THROW_ON_ERROR);
        $second = json_decode($tool($this->id('dentist'), ['walk'], 'home'), true, 512, JSON_THROW_ON_ERROR);

        self::assertSame('origin', $first['field']);
        self::assertStringContainsString('isHome=true', $first['error']);
        self::assertSame('origin', $second['field']);
        self::assertCount(1, $this->peopleRequests);
    }

    public function testEstimateTripFromHomeUsesTheSavedHomeAndAsksGoogleNothing(): void
    {
        $this->loginFixtureUser('other_user');
        $tool = self::getContainer()->get(EstimateTripTool::class);

        $estimate = json_decode($tool($this->id('other_dentist'), ['walk'], 'home'), true, 512, JSON_THROW_ON_ERROR);

        self::assertSame(['kind' => 'place', 'id' => (string) $this->placesOf('other_user')[0]->getId(), 'label' => 'la maison', 'latitude' => 48.1101, 'longitude' => -1.6801], $estimate['estimates'][0]['origin']);
        self::assertCount(0, $this->peopleRequests);
    }
}
