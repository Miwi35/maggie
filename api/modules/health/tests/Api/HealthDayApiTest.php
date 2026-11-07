<?php

declare(strict_types=1);

namespace Maggie\Health\Tests\Api;

use App\Tests\Support\AuthenticatedTestTrait;
use App\Tests\Support\FixtureLoaderTrait;
use App\Tests\Support\MercureAssertionTrait;
use Doctrine\ORM\EntityManagerInterface;
use Maggie\Core\Entity\User;
use Maggie\Health\Entity\HealthDay;
use Maggie\Health\Enum\HealthDayAuthor;
use PHPUnit\Framework\Attributes\DataProvider;
use Symfony\Bundle\FrameworkBundle\KernelBrowser;
use Symfony\Bundle\FrameworkBundle\Test\WebTestCase;
use Symfony\Component\Clock\Clock;
use Symfony\Component\Clock\MockClock;
use Symfony\Component\Clock\NativeClock;

/** The health journal over REST (MAG-750): read a period, read a day, write mood, fatigue and note. */
class HealthDayApiTest extends WebTestCase
{
    use FixtureLoaderTrait;
    use AuthenticatedTestTrait;
    use MercureAssertionTrait;

    private const string ENDPOINT = '/api/health/days';

    private KernelBrowser $client;

    protected function setUp(): void
    {
        $this->client = self::createClient();
        Clock::set(new MockClock(new \DateTimeImmutable('2026-10-25T21:42:00+02:00')));
        $this->loadFixtures('health_day.yaml');
        $this->authenticateAsUser($this->user('test_user'));
        $this->resetMercure();
    }

    protected function tearDown(): void
    {
        Clock::set(new NativeClock());
        parent::tearDown();
    }

    private function user(string $ref): User
    {
        $user = $this->getFixture($ref);
        \assert($user instanceof User);

        return $user;
    }

    private function em(): EntityManagerInterface
    {
        return self::getContainer()->get('doctrine.orm.entity_manager');
    }

    private function stored(string $ref, string $date): ?HealthDay
    {
        $this->em()->clear();

        return $this->em()->getRepository(HealthDay::class)->findOneBy(['user' => $this->user($ref)->getId(), 'date' => new \DateTimeImmutable($date)]);
    }

    /** @return array<string, mixed> */
    private function json(): array
    {
        return json_decode((string) $this->client->getResponse()->getContent(), true, 512, JSON_THROW_ON_ERROR);
    }

    /** @param array<string, mixed> $body */
    private function patch(string $date, array $body): void
    {
        $this->client->request('PATCH', self::ENDPOINT.'/'.$date, [], [], $this->authHeaders() + ['CONTENT_TYPE' => 'application/json'], json_encode($body, JSON_THROW_ON_ERROR));
    }

    public function testUnauthenticatedReturns401(): void
    {
        $this->client->request('GET', self::ENDPOINT.'?from=2026-10-20&to=2026-10-21');
        self::assertResponseStatusCodeSame(401);

        $this->client->request('GET', self::ENDPOINT.'/2026-10-20');
        self::assertResponseStatusCodeSame(401);

        $this->client->request('PATCH', self::ENDPOINT.'/2026-10-20', [], [], ['CONTENT_TYPE' => 'application/json'], '{"mood": 7}');
        self::assertResponseStatusCodeSame(401);
    }

    public function testTheDaysAreLaidDownFromTheirSources(): void
    {
        $this->client->request('GET', self::ENDPOINT.'?from=2026-10-19&to=2026-10-23', [], [], $this->authHeaders());
        self::assertResponseIsSuccessful();
        $days = $this->json()['days'];

        // The 22nd has a planned ride only: nothing was done, no entry.
        self::assertSame(['2026-10-20', '2026-10-21'], array_column($days, 'date'));
        self::assertSame(72.4, $days[0]['weightKg']);
        self::assertSame(21.3, $days[0]['bodyFatPercent']);
        self::assertSame('2026-10-20T05:00:00+00:00', (new \DateTimeImmutable($days[0]['weighedAt']))->setTimezone(new \DateTimeZone('UTC'))->format(\DATE_ATOM));
        self::assertSame(8000, $days[0]['steps']);
        self::assertSame(78, $days[0]['eatenKcal']);
        self::assertSame(32, $days[0]['sportMinutes']);
        self::assertIsInt($days[0]['expendedKcal']);
        self::assertSame(78 - $days[0]['expendedKcal'], $days[0]['balanceKcal']);
        // The 21st has no weighing: empty, never the day before's.
        self::assertNull($days[1]['weightKg']);
        self::assertNull($days[1]['steps']);
        self::assertNull($days[1]['eatenKcal']);
        self::assertSame(40, $days[1]['sportMinutes']);
    }

    public function testAnotherUsersDaysAreNeverRead(): void
    {
        $this->client->request('GET', self::ENDPOINT.'/2026-10-20', [], [], $this->authHeaders());

        self::assertResponseIsSuccessful();
        self::assertSame(72.4, $this->json()['weightKg']);
        self::assertSame(78, $this->json()['eatenKcal']);
    }

    public function testAnUnknownDayIsAnEmptyEntry(): void
    {
        $this->client->request('GET', self::ENDPOINT.'/2026-09-01', [], [], $this->authHeaders());

        self::assertResponseIsSuccessful();
        self::assertSame(HealthDay::empty('2026-09-01'), $this->json());
    }

    /** @return iterable<string, array{string}> */
    public static function badQueries(): iterable
    {
        yield 'no dates' => [''];
        yield 'no to' => ['from=2026-10-20'];
        yield 'invalid date' => ['from=2026-02-30&to=2026-03-01'];
        yield 'from after to' => ['from=2026-10-21&to=2026-10-20'];
        yield 'too long' => ['from=2025-01-01&to=2026-10-20'];
    }

    #[DataProvider('badQueries')]
    public function testABadPeriodReturns400(string $query): void
    {
        $this->client->request('GET', self::ENDPOINT.'?'.$query, [], [], $this->authHeaders());

        self::assertResponseStatusCodeSame(400);
        self::assertArrayHasKey('error', $this->json());
    }

    public function testABadDateReturns400(): void
    {
        $this->client->request('GET', self::ENDPOINT.'/yesterday', [], [], $this->authHeaders());
        self::assertResponseStatusCodeSame(400);

        $this->patch('2026-02-30', ['mood' => 7]);
        self::assertResponseStatusCodeSame(400);
    }

    public function testMoodFatigueAndNoteAreWrittenWithTheirAuthor(): void
    {
        $this->patch('2026-10-20', ['mood' => 7, 'fatigue' => 4, 'note' => '  Bonne journée, un peu de fatigue le soir.  ']);

        self::assertResponseIsSuccessful();
        self::assertSame(7, $this->json()['mood']);
        self::assertSame('user', $this->json()['notedBy']);
        $day = $this->stored('test_user', '2026-10-20');
        self::assertNotNull($day);
        self::assertSame(7, $day->getMood());
        self::assertSame(4, $day->getFatigue());
        self::assertSame('Bonne journée, un peu de fatigue le soir.', $day->getNote());
        self::assertSame(HealthDayAuthor::User, $day->getNotedBy());
        self::assertSame('2026-10-25T19:42:00+00:00', $day->getNotedAt()?->setTimezone(new \DateTimeZone('UTC'))->format(\DATE_ATOM));
        // The computed columns are untouched.
        self::assertSame(72.4, $day->getWeightKg());
        $this->assertMercureUpdatePublished('/api/health/days/2026-10-20');
    }

    public function testOnlyTheFieldsSentChangeAndNullEmptiesOne(): void
    {
        $this->patch('2026-10-20', ['mood' => 7, 'fatigue' => 4, 'note' => 'Note']);
        $this->patch('2026-10-20', ['fatigue' => 6, 'note' => null]);

        self::assertResponseIsSuccessful();
        $day = $this->stored('test_user', '2026-10-20');
        self::assertSame(7, $day?->getMood());
        self::assertSame(6, $day?->getFatigue());
        self::assertNull($day?->getNote());
    }

    public function testAnUnknownDateLaysDownTheDaysEntry(): void
    {
        self::assertNull($this->stored('test_user', '2026-10-24'));

        $this->patch('2026-10-24', ['mood' => 5]);

        self::assertResponseIsSuccessful();
        self::assertNotNull($this->json()['id']);
        self::assertSame(5, $this->stored('test_user', '2026-10-24')?->getMood());
        $this->assertMercureUpdatePublished('/api/health/days/2026-10-24');
    }

    /** @return iterable<string, array{array<string, mixed>}> */
    public static function badBodies(): iterable
    {
        yield 'mood under 1' => [['mood' => 0]];
        yield 'mood over 10' => [['mood' => 11]];
        yield 'fatigue over 10' => [['fatigue' => 12]];
        yield 'mood not a number' => [['mood' => '7']];
        yield 'mood not whole' => [['mood' => 7.5]];
        yield 'note not a text' => [['note' => 12]];
        yield 'note too long' => [['note' => str_repeat('a', HealthDay::MAX_NOTE_LENGTH + 1)]];
        yield 'a computed column' => [['weightKg' => 70]];
        yield 'an unknown field next to a known one' => [['mood' => 7, 'steps' => 10]];
        yield 'nothing' => [[]];
    }

    /** @param array<string, mixed> $body */
    #[DataProvider('badBodies')]
    public function testABadBodyReturns400AndChangesNothing(array $body): void
    {
        $this->patch('2026-10-20', $body);

        self::assertResponseStatusCodeSame(400);
        self::assertArrayHasKey('error', $this->json());
        $day = $this->stored('test_user', '2026-10-20');
        self::assertNull($day?->getMood());
        self::assertNull($day?->getFatigue());
        $this->assertMercureUpdateCount(0);
    }

    public function testTheWriteIsTheCallersOwn(): void
    {
        $this->patch('2026-10-20', ['mood' => 2]);

        self::assertNull($this->stored('other_user', '2026-10-20')?->getMood());
        self::assertSame(2, $this->stored('test_user', '2026-10-20')?->getMood());
    }

    public function testTodaysBalanceIsTheLiveOne(): void
    {
        $this->patch('2026-10-25', ['mood' => 6]);

        // Nothing eaten and no meal planned today: the live balance is minus what was spent so far.
        self::assertResponseIsSuccessful();
        self::assertNull($this->json()['eatenKcal']);
        self::assertIsInt($this->json()['balanceKcal']);
        self::assertSame(-$this->json()['expendedKcal'], $this->json()['balanceKcal']);
    }
}
