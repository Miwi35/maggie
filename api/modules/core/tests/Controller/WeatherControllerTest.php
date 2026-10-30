<?php

declare(strict_types=1);

namespace Maggie\Core\Tests\Controller;

use App\Tests\Support\AuthenticatedTestTrait;
use App\Tests\Support\FixtureLoaderTrait;
use Maggie\Core\Entity\UserPreference;
use Maggie\Core\Enum\WeatherCondition;
use Maggie\Core\Weather\OpenMeteoClient;
use PHPUnit\Framework\Attributes\DataProvider;
use Symfony\Bundle\FrameworkBundle\KernelBrowser;
use Symfony\Bundle\FrameworkBundle\Test\WebTestCase;
use Symfony\Component\Cache\Adapter\ArrayAdapter;
use Symfony\Component\HttpClient\MockHttpClient;
use Symfony\Component\HttpClient\Response\MockResponse;
use Symfony\Contracts\HttpClient\ResponseInterface;

/** GET /api/weather (MAG-710). Open-Meteo is never reached: a MockHttpClient answers. */
final class WeatherControllerTest extends WebTestCase
{
    use FixtureLoaderTrait;
    use AuthenticatedTestTrait;

    private KernelBrowser $client;

    /** @var array<string, mixed> */
    private array $daily = [];

    private bool $down = false;

    /** @var list<string> */
    private array $requested = [];

    protected function setUp(): void
    {
        $this->client = self::createClient();
        $this->client->disableReboot();
        $this->loadFixtures('user_preference.yaml');
        $this->authenticateAsUser($this->getFixture('test_user'));
        $this->daily = [
            'weather_code' => 2,
            'temperature_2m_max' => 12.4,
            'temperature_2m_min' => 5.0,
            'precipitation_sum' => 0.0,
            'snowfall_sum' => 0.0,
            'precipitation_probability_max' => 20,
            'wind_speed_10m_max' => 15.0,
            'wind_gusts_10m_max' => 30.0,
        ];
        $this->requested = [];
        $this->down = false;
        $this->useOpenMeteo();
    }

    private function useOpenMeteo(): void
    {
        $http = new MockHttpClient(function (string $method, string $url): ResponseInterface {
            $this->requested[] = $url;
            if ($this->down) {
                return new MockResponse('', ['http_code' => 500]);
            }
            if (str_contains($url, '/search')) {
                return self::json(['results' => [['name' => 'Rennes', 'country' => 'France', 'latitude' => 48.11, 'longitude' => -1.68]]]);
            }
            preg_match('/start_date=([\d-]+)/', $url, $range);

            return self::json(['daily' => ['time' => [$range[1]]] + array_map(static fn (mixed $value): array => [$value], $this->daily)]);
        });

        self::getContainer()->set(OpenMeteoClient::class, new OpenMeteoClient($http, new ArrayAdapter(), 'http://open-meteo.test/forecast', 'http://open-meteo.test/search'));
    }

    /** @param array<string, mixed> $body */
    private static function json(array $body): MockResponse
    {
        return new MockResponse(json_encode($body, \JSON_THROW_ON_ERROR), [
            'http_code' => 200,
            'response_headers' => ['content-type: application/json'],
        ]);
    }

    private function today(): string
    {
        return (new \DateTimeImmutable('today', new \DateTimeZone('Europe/Paris')))->format('Y-m-d');
    }

    private function get(string $query, bool $authenticated = true): void
    {
        $this->client->request('GET', '/api/weather'.$query, [], [], $authenticated ? $this->authHeaders() : []);
    }

    /** @return array<string, mixed> */
    private function body(): array
    {
        return json_decode((string) $this->client->getResponse()->getContent(), true, 512, \JSON_THROW_ON_ERROR);
    }

    private function setCity(?string $city): void
    {
        $em = self::getContainer()->get('doctrine.orm.entity_manager');
        $preference = $em->getRepository(UserPreference::class)->findOneBy(['user' => $this->getFixture('test_user')]);
        $preference->setDefaultCity($city);
        $em->flush();
    }

    public function testUnauthenticatedReturns401(): void
    {
        $this->get('?date='.$this->today(), authenticated: false);

        self::assertResponseStatusCodeSame(401);
        self::assertSame([], $this->requested);
    }

    public static function invalidDates(): iterable
    {
        yield 'missing' => [''];
        yield 'not a date' => ['?date=demain'];
        yield 'impossible day' => ['?date=2026-02-30'];
        yield 'in the past' => ['?date=2020-01-01'];
        yield 'beyond 15 days' => ['?date=2099-01-01'];
    }

    #[DataProvider('invalidDates')]
    public function testAnInvalidOrOutOfRangeDateReturns400(string $query): void
    {
        $this->setCity('Rennes');

        $this->get($query);

        self::assertResponseStatusCodeSame(400);
        self::assertSame('date', $this->body()['field']);
        self::assertSame([], $this->requested);
    }

    public function testWithoutACityReturns404(): void
    {
        $this->setCity(null);

        $this->get('?date='.$this->today());

        self::assertResponseStatusCodeSame(404);
        self::assertSame('Aucune ville', $this->body()['error']);
    }

    public function testTheDayIsReadForTheDefaultCity(): void
    {
        $this->setCity('Rennes');

        $this->get('?date='.$this->today());

        self::assertResponseIsSuccessful();
        self::assertSame([
            'date' => $this->today(),
            'minC' => 5,
            'maxC' => 12.4,
            'rainMm' => 0,
            'snowCm' => 0,
            'rainProbability' => 20,
            'windKmh' => 15,
            'alerts' => [],
            'condition' => 'partly_cloudy',
        ], $this->body());
    }

    public function testTheAlertsOfTheToolAreReturned(): void
    {
        $this->setCity('Rennes');
        $this->daily['weather_code'] = 95;
        $this->daily['temperature_2m_max'] = 36.0;

        $this->get('?date='.$this->today());

        self::assertResponseIsSuccessful();
        self::assertSame(['orage', 'forte chaleur'], $this->body()['alerts']);
        self::assertSame('storm', $this->body()['condition']);
    }

    public function testAWeatherServiceDownReturns503(): void
    {
        $this->setCity('Rennes');
        $this->down = true;

        $this->get('?date='.$this->today());

        self::assertResponseStatusCodeSame(503);
    }

    /** @return iterable<string, array{int, ?WeatherCondition}> */
    public static function wmoCodes(): iterable
    {
        foreach ([0, 1] as $code) {
            yield "clear $code" => [$code, WeatherCondition::Clear];
        }
        yield 'partly cloudy' => [2, WeatherCondition::PartlyCloudy];
        yield 'cloudy' => [3, WeatherCondition::Cloudy];
        foreach ([45, 48] as $code) {
            yield "fog $code" => [$code, WeatherCondition::Fog];
        }
        foreach ([51, 53, 55, 56, 57] as $code) {
            yield "drizzle $code" => [$code, WeatherCondition::Drizzle];
        }
        foreach ([61, 63, 65, 66, 67] as $code) {
            yield "rain $code" => [$code, WeatherCondition::Rain];
        }
        foreach ([71, 73, 75, 77, 85, 86] as $code) {
            yield "snow $code" => [$code, WeatherCondition::Snow];
        }
        foreach ([80, 81, 82] as $code) {
            yield "showers $code" => [$code, WeatherCondition::Showers];
        }
        foreach ([95, 96, 99] as $code) {
            yield "storm $code" => [$code, WeatherCondition::Storm];
        }
        yield 'unknown' => [42, null];
    }

    #[DataProvider('wmoCodes')]
    public function testEveryWmoCodeFallsInItsGroup(int $code, ?WeatherCondition $expected): void
    {
        self::assertSame($expected, WeatherCondition::fromWmo($code));
    }
}
