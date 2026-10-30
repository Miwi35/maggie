<?php

declare(strict_types=1);

namespace Maggie\Health\Tests\Api;

use App\Tests\Support\AuthenticatedTestTrait;
use App\Tests\Support\ElasticsearchAssertionTrait;
use App\Tests\Support\FixtureLoaderTrait;
use App\Tests\Support\MercureAssertionTrait;
use Doctrine\ORM\EntityManagerInterface;
use Maggie\Core\Entity\User;
use Maggie\Health\Entity\BodyMeasurement;
use Maggie\Health\Entity\HealthProfile;
use Maggie\Health\Enum\ActivityLevel;
use Maggie\Health\Enum\MeasurementSource;
use Maggie\Health\Enum\NeedMode;
use Maggie\Health\Enum\Sex;
use Maggie\Health\Enum\StepSource;
use PHPUnit\Framework\Attributes\DataProvider;
use Symfony\Bundle\FrameworkBundle\KernelBrowser;
use Symfony\Bundle\FrameworkBundle\Test\WebTestCase;
use Symfony\Component\Clock\Clock;
use Symfony\Component\Clock\MockClock;
use Symfony\Component\Clock\NativeClock;

/** The health profile over REST (MAG-707): GET and a PATCH that applies only the fields it is sent. */
class HealthProfileApiTest extends WebTestCase
{
    use FixtureLoaderTrait;
    use AuthenticatedTestTrait;
    use MercureAssertionTrait;
    use ElasticsearchAssertionTrait;

    private const string ENDPOINT = '/api/health/profile';

    private KernelBrowser $client;

    protected function setUp(): void
    {
        $this->client = self::createClient();
        Clock::set(new MockClock(new \DateTimeImmutable('2026-10-30T09:00:00+00:00')));
        $this->loadFixtures('health_profile.yaml');
        $this->authenticateAsUser($this->user('test_user'));
        $this->resetMercure();
        $this->resetAsyncTransport();
    }

    protected function tearDown(): void
    {
        Clock::set(new NativeClock());
        parent::tearDown();
    }

    public function testUnauthenticatedReturns401(): void
    {
        $this->client->request('GET', self::ENDPOINT);
        self::assertResponseStatusCodeSame(401);

        $this->client->request('PATCH', self::ENDPOINT, [], [], ['CONTENT_TYPE' => 'application/json'], '{"heightCm": 178}');
        self::assertResponseStatusCodeSame(401);
    }

    public function testBeforeAnyWriteTheReadGivesTheDefaultsAndTheLatestWeighingWithoutSavingAnything(): void
    {
        $this->client->request('GET', self::ENDPOINT, [], [], $this->authHeaders());

        self::assertResponseIsSuccessful();
        self::assertSame([
            'id' => null,
            'sex' => null,
            'birthYear' => null,
            'age' => null,
            'heightCm' => null,
            // The weighing of the 28th: the 90 kg of the 29th is set aside, the waist size has no weight.
            // A JSON number: 72, not 72.0.
            'weightKg' => 72,
            'activityLevel' => 'sedentary',
            'mode' => 'computed',
            'enteredNeedKcal' => null,
            'bmrKcal' => null,
            'needKcal' => null,
            'factor' => 1.2,
            'stepGoal' => 8000,
            'suggestionWindowStart' => '08:00',
            'suggestionWindowEnd' => '20:00',
            'sessionMinutes' => 20,
            'stepSource' => 'none',
        ], $this->decoded());
        self::assertNull($this->profileOf('test_user'));
        $this->assertMercureUpdateCount(0);
    }

    public function testAManOf38178CmAnd72KgSedentaryNeeds1980KcalOnABasalMetabolismOf1648(): void
    {
        $data = $this->patch(['sex' => 'male', 'birthYear' => 1988, 'heightCm' => 178, 'activityLevel' => 'sedentary']);

        self::assertResponseIsSuccessful();
        self::assertSame(38, $data['age']);
        self::assertEquals(72.0, $data['weightKg']);
        self::assertSame(1648, $data['bmrKcal']);
        self::assertSame(1980, $data['needKcal']);

        $stored = $this->profileOf('test_user');
        self::assertNotNull($stored);
        self::assertSame($data['id'], (string) $stored->getId());
        self::assertSame(Sex::Male, $stored->getSex());
        self::assertSame(1988, $stored->getBirthYear());
        self::assertSame(178, $stored->getHeightCm());

        $payloads = $this->mercurePayloadsOn('/api/health-profile/'.$stored->getId());
        self::assertNotEmpty($payloads);
        self::assertSame(1980, end($payloads)['needKcal']);
        self::assertSame(1648, end($payloads)['bmrKcal']);
        // Not searched, so never indexed.
        $this->assertNoElasticsearchIndexDispatched(HealthProfile::class);

        // Another user's profile is not touched.
        self::assertSame(165, $this->profileOf('other_user')?->getHeightCm());
    }

    public function testAPartialPatchKeepsTheFieldsItIsNotSent(): void
    {
        $this->patch(['sex' => 'male', 'birthYear' => 1988, 'heightCm' => 178, 'stepGoal' => 9000]);

        $data = $this->patch(['sessionMinutes' => 30]);

        self::assertResponseIsSuccessful();
        $stored = $this->profileOf('test_user');
        self::assertNotNull($stored);
        self::assertSame(30, $stored->getSessionMinutes());
        self::assertSame(178, $stored->getHeightCm());
        self::assertSame(9000, $stored->getStepGoal());
        self::assertSame(ActivityLevel::Sedentary, $stored->getActivityLevel());
        self::assertSame(1980, $data['needKcal']);
    }

    public function testTheEnteredModeGivesTheNeedTypedAndItsRatioToTheBasalMetabolism(): void
    {
        $this->patch(['sex' => 'male', 'birthYear' => 1988, 'heightCm' => 178]);

        $data = $this->patch(['mode' => 'entered', 'enteredNeedKcal' => 2200]);

        self::assertResponseIsSuccessful();
        self::assertSame(2200, $data['needKcal']);
        self::assertSame(1648, $data['bmrKcal']);
        self::assertSame(1.33, $data['factor']);
        self::assertSame(NeedMode::Entered, $this->profileOf('test_user')?->getMode());

        // Back to computed: the need typed is kept for the next switch, the computed one shown.
        $data = $this->patch(['mode' => 'computed']);
        self::assertSame(1980, $data['needKcal']);
        self::assertSame(2200, $data['enteredNeedKcal']);
    }

    public function testTheActivitySettingsAreStored(): void
    {
        $data = $this->patch(['stepGoal' => 10000, 'suggestionWindowStart' => '09:30', 'suggestionWindowEnd' => '19:00', 'sessionMinutes' => 15, 'stepSource' => 'health_connect']);

        self::assertResponseIsSuccessful();
        self::assertSame(10000, $data['stepGoal']);
        $stored = $this->profileOf('test_user');
        self::assertNotNull($stored);
        self::assertSame('09:30', $stored->getSuggestionWindowStart());
        self::assertSame('19:00', $stored->getSuggestionWindowEnd());
        self::assertSame(15, $stored->getSessionMinutes());
        self::assertSame(StepSource::HealthConnect, $stored->getStepSource());
    }

    public function testAWeightRecordsAManualWeighingTakenNowWhichBecomesTheProfilesWeight(): void
    {
        $this->patch(['sex' => 'male', 'birthYear' => 1988, 'heightCm' => 178]);
        $this->resetMercure();

        $data = $this->patch(['weightKg' => 73.4]);

        self::assertResponseIsSuccessful();
        self::assertSame(73.4, $data['weightKg']);
        // 734 + 1112.5 − 190 + 5 = 1661.5 → 1662; × 1.2 = 1994.4 → 1990.
        self::assertSame(1662, $data['bmrKcal']);
        self::assertSame(1990, $data['needKcal']);

        $weighing = $this->em()->getRepository(BodyMeasurement::class)->findOneBy(['user' => $this->user('test_user')->getId(), 'weight' => '73.40']);
        self::assertNotNull($weighing);
        self::assertSame(MeasurementSource::Manual, $weighing->getSource());
        self::assertSame('2026-10-30T09:00:00+00:00', $weighing->getMeasuredAt()->format(\DATE_ATOM));
        self::assertFalse($weighing->isExcluded());
        $this->assertMercureUpdatePublished('/api/body-measurements/'.$weighing->getId());

        $profile = $this->profileOf('test_user');
        self::assertNotNull($profile);
        $payloads = $this->mercurePayloadsOn('/api/health-profile/'.$profile->getId());
        self::assertSame(73.4, end($payloads)['weightKg']);
        self::assertSame(1990, end($payloads)['needKcal']);
    }

    /** @return iterable<string, array{string}> */
    public static function bodiesThatAreRefused(): iterable
    {
        yield 'nothing' => ['{}'];
        yield 'not JSON' => ['tall'];
        yield 'an unknown field' => ['{"heightCm": 178, "goal": "lose"}'];
        yield 'a height under 100' => ['{"heightCm": 99}'];
        yield 'a height over 250' => ['{"heightCm": 251}'];
        yield 'a height as a string' => ['{"heightCm": "178"}'];
        yield 'an entered need under 1000' => ['{"enteredNeedKcal": 950}'];
        yield 'an entered need over 5000' => ['{"enteredNeedKcal": 5050}'];
        yield 'an entered need off the 50 kcal step' => ['{"enteredNeedKcal": 2025}'];
        yield 'a session of 25 minutes' => ['{"sessionMinutes": 25}'];
        yield 'an unknown sex' => ['{"sex": "other"}'];
        yield 'an unknown activity' => ['{"activityLevel": "athlete"}'];
        yield 'an unknown mode' => ['{"mode": "guessed"}'];
        yield 'a year of birth to come' => ['{"birthYear": 2027}'];
        yield 'a step goal off the 500 step' => ['{"stepGoal": 8250}'];
        yield 'a window that ends before it starts' => ['{"suggestionWindowStart": "20:00", "suggestionWindowEnd": "08:00"}'];
        yield 'a time that is not HH:MM' => ['{"suggestionWindowStart": "8h"}'];
        yield 'an unknown step source' => ['{"stepSource": "fitbit"}'];
        yield 'a weight of zero' => ['{"weightKg": 0}'];
        yield 'a weight over 500' => ['{"weightKg": 501}'];
        yield 'a valid field with a refused one' => ['{"stepGoal": 10000, "sessionMinutes": 25}'];
    }

    #[DataProvider('bodiesThatAreRefused')]
    public function testARefusedBodyIsA400AndChangesNothing(string $body): void
    {
        $this->patch(['heightCm' => 178]);
        $this->resetMercure();
        $weighings = $this->em()->getRepository(BodyMeasurement::class)->count([]);

        $this->client->request('PATCH', self::ENDPOINT, [], [], $this->authHeaders() + ['CONTENT_TYPE' => 'application/json'], $body);

        self::assertResponseStatusCodeSame(400);
        $stored = $this->profileOf('test_user');
        self::assertNotNull($stored);
        self::assertSame(178, $stored->getHeightCm());
        self::assertSame(HealthProfile::DEFAULT_STEP_GOAL, $stored->getStepGoal());
        self::assertSame($weighings, $this->em()->getRepository(BodyMeasurement::class)->count([]));
        $this->assertMercureUpdateCount(0);
    }

    /**
     * @param array<string, mixed> $body
     *
     * @return array<string, mixed>
     */
    private function patch(array $body): array
    {
        $this->client->request('PATCH', self::ENDPOINT, [], [], $this->authHeaders() + ['CONTENT_TYPE' => 'application/json'], json_encode($body, JSON_THROW_ON_ERROR));

        return $this->decoded();
    }

    /** @return array<string, mixed> */
    private function decoded(): array
    {
        return json_decode((string) $this->client->getResponse()->getContent(), true, 512, JSON_THROW_ON_ERROR);
    }

    private function em(): EntityManagerInterface
    {
        $em = self::getContainer()->get(EntityManagerInterface::class);
        $em->clear();

        return $em;
    }

    private function user(string $ref): User
    {
        $user = $this->getFixture($ref);
        \assert($user instanceof User);

        return $user;
    }

    private function profileOf(string $userRef): ?HealthProfile
    {
        return $this->em()->getRepository(HealthProfile::class)->findOneBy(['user' => $this->user($userRef)->getId()]);
    }
}
