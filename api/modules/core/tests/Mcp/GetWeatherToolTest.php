<?php

namespace Maggie\Core\Tests\Mcp;

use App\Tests\Support\FixtureLoaderTrait;
use App\Tests\Support\SecurityTokenTrait;
use Maggie\Core\Entity\UserPreference;
use Maggie\Core\Mcp\McpUserContext;
use Maggie\Core\Mcp\MissingMcpUserException;
use Maggie\Core\Mcp\Tool\GetWeatherTool;
use Maggie\Core\Repository\UserPreferenceRepository;
use Maggie\Core\Weather\OpenMeteoClient;
use PHPUnit\Framework\Attributes\DataProvider;
use Symfony\Bundle\FrameworkBundle\Test\KernelTestCase;
use Symfony\Component\Cache\Adapter\ArrayAdapter;
use Symfony\Component\HttpClient\MockHttpClient;
use Symfony\Component\HttpClient\Response\MockResponse;
use Symfony\Contracts\HttpClient\ResponseInterface;

/** Open-Meteo is never reached: every call goes through a MockHttpClient. */
class GetWeatherToolTest extends KernelTestCase
{
    use FixtureLoaderTrait;
    use SecurityTokenTrait;

    /** @var list<string> */
    private array $requested = [];

    protected function setUp(): void
    {
        self::bootKernel();
        $this->requested = [];
    }

    private function em(): \Doctrine\ORM\EntityManagerInterface
    {
        return self::getContainer()->get('doctrine.orm.entity_manager');
    }

    private function today(string $modifier = 'today'): string
    {
        return (new \DateTimeImmutable($modifier, new \DateTimeZone('Europe/Paris')))->format('Y-m-d');
    }

    /** @param callable(string, string): ResponseInterface $answer */
    private function tool(callable $answer): GetWeatherTool
    {
        $http = new MockHttpClient(function (string $method, string $url) use ($answer): ResponseInterface {
            $this->requested[] = $url;

            return $answer($method, $url);
        });

        return new GetWeatherTool(
            self::getContainer()->get(UserPreferenceRepository::class),
            self::getContainer()->get(McpUserContext::class),
            new OpenMeteoClient($http, new ArrayAdapter(), 'http://open-meteo.test/forecast', 'http://open-meteo.test/search'),
        );
    }

    /** @param array<string, mixed> $body */
    private static function json(array $body, int $status = 200): MockResponse
    {
        return new MockResponse(json_encode($body, JSON_THROW_ON_ERROR), [
            'http_code' => $status,
            'response_headers' => ['content-type: application/json'],
        ]);
    }

    /** Geocoding then forecast, like the real API. */
    private function openMeteo(): callable
    {
        return function (string $method, string $url): ResponseInterface {
            if (str_contains($url, '/search')) {
                return self::json(['results' => [
                    ['name' => 'Rennes', 'country' => 'France', 'latitude' => 48.11, 'longitude' => -1.68],
                ]]);
            }

            preg_match('/start_date=([\d-]+)&end_date=([\d-]+)/', $url, $range);
            $dates = array_values(array_unique([$range[1], $range[2]]));

            return self::json(['daily' => [
                'time' => $dates,
                'weather_code' => array_fill(0, \count($dates), 61),
                'temperature_2m_max' => array_fill(0, \count($dates), 17.5),
                'temperature_2m_min' => array_fill(0, \count($dates), 9.0),
                'precipitation_sum' => array_fill(0, \count($dates), 4.2),
                'precipitation_probability_max' => array_fill(0, \count($dates), 80),
                'wind_speed_10m_max' => array_fill(0, \count($dates), 25.0),
                'wind_gusts_10m_max' => array_fill(0, \count($dates), 45.0),
            ]]);
        };
    }

    /** @return array<string, mixed> */
    private function call(GetWeatherTool $tool, ?string $date = null, ?string $location = null): array
    {
        return json_decode($tool($date, $location), true, 512, JSON_THROW_ON_ERROR);
    }

    private function loggedInWithCity(?string $city): UserPreference
    {
        $this->loadFixtures(__DIR__.'/../Controller/fixtures/user_preference.yaml');
        $user = $this->loginFixtureUser();
        $preference = $this->em()->getRepository(UserPreference::class)->findOneBy(['user' => $user]);
        $preference->setDefaultCity($city);
        $this->em()->flush();

        return $preference;
    }

    public function testWithoutAUserBoundReturnsAnErrorAndCallsNothing(): void
    {
        $this->loadFixtures(__DIR__.'/../Controller/fixtures/user_preference.yaml');

        $result = $this->call($this->tool($this->openMeteo()), null, 'Rennes');

        self::assertSame(MissingMcpUserException::MESSAGE, $result['error']);
        self::assertSame([], $this->requested);
    }

    public function testWithoutDateAnswersTodayAndTomorrowForTheDefaultCity(): void
    {
        $this->loggedInWithCity('Rennes');

        $result = $this->call($this->tool($this->openMeteo()));

        self::assertArrayNotHasKey('error', $result);
        self::assertSame(['name' => 'Rennes', 'country' => 'France'], $result['location']);
        self::assertSame([$this->today(), $this->today('tomorrow')], array_column($result['days'], 'date'));
        self::assertSame([
            'date' => $this->today(),
            'condition' => 'pluie faible',
            'temperature_min_c' => 9,
            'temperature_max_c' => 17.5,
            'rain_mm' => 4.2,
            'rain_probability_percent' => 80,
            'wind_max_kmh' => 25,
            'wind_gusts_max_kmh' => 45,
            'alerts' => [],
        ], $result['days'][0]);
        self::assertStringContainsString('name=Rennes', $this->requested[0]);
    }

    public function testAGivenLocationWinsOverTheDefaultCity(): void
    {
        $this->loggedInWithCity('Rennes');

        $this->call($this->tool($this->openMeteo()), null, 'Brest');

        self::assertStringContainsString('name=Brest', $this->requested[0]);
    }

    public function testADateGivesThatDayOnly(): void
    {
        $this->loggedInWithCity('Rennes');
        $date = $this->today('+3 days');

        $result = $this->call($this->tool($this->openMeteo()), $date);

        self::assertSame([$date], array_column($result['days'], 'date'));
    }

    public function testAlertsAreDerivedFromTheForecast(): void
    {
        $this->loggedInWithCity('Rennes');
        $tool = $this->tool(function (string $method, string $url): ResponseInterface {
            if (str_contains($url, '/search')) {
                return self::json(['results' => [['name' => 'Rennes', 'latitude' => 48.11, 'longitude' => -1.68]]]);
            }

            return self::json(['daily' => [
                'time' => [$this->today()],
                'weather_code' => [95],
                'temperature_2m_max' => [36.0],
                'temperature_2m_min' => [20.0],
                'precipitation_sum' => [25.0],
                'precipitation_probability_max' => [100],
                'wind_speed_10m_max' => [50.0],
                'wind_gusts_10m_max' => [75.0],
            ]]);
        });

        $day = $this->call($tool, $this->today())['days'][0];

        self::assertSame(['orage', 'vent violent', 'fortes pluies', 'forte chaleur'], $day['alerts']);
    }

    public function testWithoutAnyLocationItAsksForOneAndCallsNothing(): void
    {
        $this->loggedInWithCity(null);

        $result = $this->call($this->tool($this->openMeteo()));

        self::assertStringContainsString('No location', $result['error']);
        self::assertSame([], $this->requested);
    }

    #[DataProvider('badDates')]
    public function testABadDateIsRejectedBeforeAnyCall(string $date): void
    {
        $this->loggedInWithCity('Rennes');

        $result = $this->call($this->tool($this->openMeteo()), $date);

        self::assertArrayHasKey('error', $result);
        self::assertSame([], $this->requested);
    }

    /** @return iterable<string, array{string}> */
    public static function badDates(): iterable
    {
        yield 'not a date' => ['demain'];
        yield 'impossible day' => ['2026-02-30'];
        yield 'wrong format' => ['06/10/2026'];
        yield 'in the past' => ['2020-01-01'];
        yield 'too far ahead' => [(new \DateTimeImmutable('+40 days'))->format('Y-m-d')];
    }

    public function testAnUnknownPlaceIsReportedAsSuch(): void
    {
        $this->loggedInWithCity('Rennes');

        $result = $this->call($this->tool(fn () => self::json(['generationtime_ms' => 0.4])), null, 'Zzzzqx');

        self::assertSame('No place found for "Zzzzqx".', $result['error']);
    }

    /** @return iterable<string, array{string}> */
    public static function addressLikeCities(): iterable
    {
        yield 'with a postcode' => ['Rennes 35000'];
        yield 'postcode first' => ['35000 Rennes'];
        yield 'with a department in brackets' => ['Rennes (35)'];
        yield 'with a region' => ['Rennes, Ille-et-Vilaine'];
    }

    /** Open-Meteo's geocoder matches a bare place name only: a postcode or a bracket finds nothing. */
    #[DataProvider('addressLikeCities')]
    public function testACityWrittenLikeAnAddressStillFindsItsForecast(string $city): void
    {
        $this->loggedInWithCity($city);
        $openMeteo = $this->openMeteo();

        $tool = $this->tool(function (string $method, string $url) use ($openMeteo): ResponseInterface {
            if (str_contains($url, '/search')) {
                parse_str((string) parse_url($url, PHP_URL_QUERY), $query);
                if ('Rennes' !== $query['name']) {
                    return self::json(['generationtime_ms' => 0.4]);
                }
            }

            return $openMeteo($method, $url);
        });

        $result = $this->call($tool);

        self::assertArrayNotHasKey('error', $result);
        self::assertSame('Rennes', $result['location']['name']);
        self::assertCount(2, $result['days']);
    }

    public function testAnOpenMeteoOutageGivesAClearErrorInsteadOfThrowing(): void
    {
        $this->loggedInWithCity('Rennes');

        $down = $this->call($this->tool(fn () => self::json(['reason' => 'down'], 503)));
        $unreachable = $this->call($this->tool(fn () => new MockResponse('', ['error' => 'Connection refused'])));
        $garbage = $this->call($this->tool(fn () => new MockResponse('<html>oops</html>')));

        foreach ([$down, $unreachable, $garbage] as $result) {
            self::assertStringContainsString('unavailable', $result['error']);
            self::assertArrayNotHasKey('days', $result);
        }
    }

    public function testAForecastWithoutDailyDataIsAnOutage(): void
    {
        $this->loggedInWithCity('Rennes');
        $tool = $this->tool(fn (string $m, string $url) => str_contains($url, '/search')
            ? self::json(['results' => [['name' => 'Rennes', 'latitude' => 48.11, 'longitude' => -1.68]]])
            : self::json(['error' => true]));

        self::assertStringContainsString('unavailable', $this->call($tool)['error']);
    }

    public function testASecondAskIsServedFromTheCache(): void
    {
        $this->loggedInWithCity('Rennes');
        $tool = $this->tool($this->openMeteo());

        $this->call($tool);
        $this->call($tool);

        self::assertCount(2, $this->requested, 'one geocoding + one forecast, not four calls');
    }

    public function testAFailureIsNotCached(): void
    {
        $this->loggedInWithCity('Rennes');
        $fail = true;
        $ok = $this->openMeteo();
        $tool = $this->tool(function (string $method, string $url) use (&$fail, $ok): ResponseInterface {
            return $fail ? self::json([], 500) : $ok($method, $url);
        });

        self::assertArrayHasKey('error', $this->call($tool));
        $fail = false;

        self::assertArrayNotHasKey('error', $this->call($tool));
    }

    public function testAnInvalidStoredTimezoneFallsBackToParisInsteadOfThrowing(): void
    {
        $this->loggedInWithCity('Rennes')->setTimezone('Not/AZone');
        $this->em()->flush();

        $result = $this->call($this->tool($this->openMeteo()));

        self::assertArrayNotHasKey('error', $result);
        self::assertSame([$this->today(), $this->today('tomorrow')], array_column($result['days'], 'date'));
    }

    public function testIsRegisteredAsAnMcpToolWithItsDependenciesWired(): void
    {
        self::assertInstanceOf(GetWeatherTool::class, self::getContainer()->get(GetWeatherTool::class));
    }
}
