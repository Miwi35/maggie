<?php

declare(strict_types=1);

namespace Maggie\Health\Tests\Api;

use App\Tests\Support\AuthenticatedTestTrait;
use App\Tests\Support\ElasticsearchAssertionTrait;
use App\Tests\Support\FixtureLoaderTrait;
use App\Tests\Support\MercureAssertionTrait;
use Doctrine\ORM\EntityManagerInterface;
use Maggie\Core\Entity\User;
use Maggie\Health\Entity\HealthDay;
use Symfony\Bundle\FrameworkBundle\KernelBrowser;
use Symfony\Bundle\FrameworkBundle\Test\WebTestCase;
use Symfony\Component\Clock\Clock;
use Symfony\Component\Clock\MockClock;
use Symfony\Component\Clock\NativeClock;

/** A day's intermittent fast (MAG-770): `GET /api/health/fasting`, read from the food journal, and the journal's night's fast. */
class FastingApiTest extends WebTestCase
{
    use FixtureLoaderTrait;
    use AuthenticatedTestTrait;
    use ElasticsearchAssertionTrait;
    use MercureAssertionTrait;

    private KernelBrowser $client;

    protected function setUp(): void
    {
        $this->client = self::createClient();
        $this->loadFixtures('fasting.yaml');
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
        $this->client->request('GET', '/api/health/fasting?date=2026-11-10');

        self::assertResponseStatusCodeSame(401);
    }

    public function testABadDateIs400(): void
    {
        $this->read('fasting-test@example.com', '2026-11-31', '2026-11-10T15:00:00+01:00');

        self::assertResponseStatusCodeSame(400);
    }

    public function testFirstIntakeAt1210AfterLastAt2005TheWindowIsOpenAfterAFastOf16h05(): void
    {
        self::assertSame([
            'date' => '2026-11-10',
            'protocol' => '16:8',
            'windowStart' => '12:00',
            'windowEnd' => '20:00',
            'openedAt' => '2026-11-10T12:10:00+01:00',
            'lastIntakeAt' => '2026-11-10T12:10:00+01:00',
            'state' => 'open',
            'nightFastMinutes' => 965,
        ], $this->read('fasting-test@example.com', '2026-11-10', '2026-11-10T15:00:00+01:00'));
    }

    public function testWithoutAnIntakeYetAt10hTheUserIsFastingSinceTheLastOne(): void
    {
        $data = $this->read('fasting-test@example.com', '2026-11-11', '2026-11-11T10:00:00+01:00');

        self::assertSame('fasting', $data['state']);
        self::assertNull($data['openedAt']);
        self::assertSame('2026-11-10T12:10:00+01:00', $data['lastIntakeAt']);
        self::assertNull($data['nightFastMinutes']);
    }

    public function testAt2030TheWindowIsClosed(): void
    {
        $data = $this->read('fasting-test@example.com', '2026-11-10', '2026-11-10T20:30:00+01:00');

        self::assertSame('closed', $data['state']);
        self::assertSame('2026-11-10T12:10:00+01:00', $data['openedAt']);
    }

    public function testAPastDayIsReadAsAtItsEnd(): void
    {
        $data = $this->read('fasting-test@example.com', '2026-11-09', '2026-11-10T15:00:00+01:00');

        self::assertSame('closed', $data['state']);
        self::assertSame('2026-11-09T20:05:00+01:00', $data['lastIntakeAt']);
    }

    public function testAFutureDayGivesItsWindowOnly(): void
    {
        self::assertSame([
            'date' => '2026-11-12',
            'protocol' => '16:8',
            'windowStart' => '12:00',
            'windowEnd' => '20:00',
            'openedAt' => null,
            'lastIntakeAt' => null,
            'state' => null,
            'nightFastMinutes' => null,
        ], $this->read('fasting-test@example.com', '2026-11-12', '2026-11-10T15:00:00+01:00'));
    }

    public function testWithoutAProtocolOnlyTheNightsFastIsGiven(): void
    {
        $data = $this->read('fasting-none@example.com', '2026-11-10', '2026-11-10T15:00:00+01:00');

        self::assertNull($data['protocol']);
        self::assertNull($data['windowStart']);
        self::assertNull($data['windowEnd']);
        self::assertNull($data['state']);
        // 19:00 → 08:00.
        self::assertSame(780, $data['nightFastMinutes']);
    }

    public function testTheJournalKeepsTheNightsFast(): void
    {
        self::assertSame(965, $this->day('fasting-test@example.com', '2026-11-10')?->getNightFastMinutes());
        // The 9th's first intake has no day before it.
        self::assertNull($this->day('fasting-test@example.com', '2026-11-09')?->getNightFastMinutes());
    }

    public function testAnIntakeAddedYesterdayAt22hRecomputesTodaysNightsFast(): void
    {
        Clock::set(new MockClock(new \DateTimeImmutable('2026-11-10T15:00:00+01:00')));
        $this->authenticateAsUser($this->user('fasting-test@example.com'));

        $this->client->request('POST', '/api/health/food-intakes', [], [], $this->authHeaders() + [
            'CONTENT_TYPE' => 'application/ld+json',
            'HTTP_ACCEPT' => 'application/ld+json',
        ], json_encode(['eatenAt' => '2026-11-09T22:00:00+01:00', 'label' => 'Yaourt', 'kcal' => 90], JSON_THROW_ON_ERROR));

        self::assertResponseStatusCodeSame(201);
        $today = $this->day('fasting-test@example.com', '2026-11-10');
        // 22:00 → 12:10.
        self::assertSame(850, $today?->getNightFastMinutes());
        $this->assertMercureUpdatePublished('/api/health/days/2026-11-10');
    }

    /** @return array<string, mixed> */
    private function read(string $email, string $date, string $now): array
    {
        Clock::set(new MockClock(new \DateTimeImmutable($now)));
        $this->authenticateAsUser($this->user($email));
        $this->client->request('GET', '/api/health/fasting?date='.$date, [], [], $this->authHeaders());

        return json_decode((string) $this->client->getResponse()->getContent(), true, 512, JSON_THROW_ON_ERROR);
    }

    private function day(string $email, string $date): ?HealthDay
    {
        $em = self::getContainer()->get(EntityManagerInterface::class);
        \assert($em instanceof EntityManagerInterface);
        $em->clear();

        return $em->getRepository(HealthDay::class)->findOneBy(['user' => $this->user($email)->getId(), 'date' => new \DateTimeImmutable($date)]);
    }

    private function user(string $email): User
    {
        $user = self::getContainer()->get(EntityManagerInterface::class)->getRepository(User::class)->findOneBy(['email' => $email]);
        \assert($user instanceof User);

        return $user;
    }
}
