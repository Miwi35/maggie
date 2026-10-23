<?php

declare(strict_types=1);

namespace Maggie\Core\Tests\Home;

use App\Tests\Support\AuthenticatedTestTrait;
use App\Tests\Support\FixtureLoaderTrait;
use Symfony\Bundle\FrameworkBundle\KernelBrowser;
use Symfony\Bundle\FrameworkBundle\Test\WebTestCase;
use Symfony\Component\Clock\Clock;
use Symfony\Component\Clock\MockClock;
use Symfony\Component\Clock\NativeClock;

/** GET /api/home/{module} (MAG-560), against the fake `leisure` card of the test kernel. */
class HomeControllerTest extends WebTestCase
{
    use FixtureLoaderTrait;
    use AuthenticatedTestTrait;

    private KernelBrowser $client;

    protected function setUp(): void
    {
        $this->client = self::createClient();
        // 00:30 on the 14th in Paris is still the 13th in UTC: « today » must follow the owner's zone.
        Clock::set(new MockClock(new \DateTimeImmutable('2026-10-14 00:30:00', new \DateTimeZone('Europe/Paris'))));
        $this->loadFixtures('HomeControllerTest.yaml');
    }

    protected function tearDown(): void
    {
        Clock::set(new NativeClock());
        parent::tearDown();
    }

    /** @return array<string, mixed> */
    private function body(): array
    {
        return json_decode((string) $this->client->getResponse()->getContent(), true, 512, JSON_THROW_ON_ERROR);
    }

    public function testUnauthenticatedIs401(): void
    {
        $this->client->request('GET', '/api/home/leisure');

        self::assertResponseStatusCodeSame(401);
    }

    public function testInvalidDayIs400Problem(): void
    {
        $this->authenticateAsUser($this->getFixture('test_user'));

        $this->client->request('GET', '/api/home/leisure?day=yesterday', [], [], $this->authHeaders());

        self::assertResponseStatusCodeSame(400);
        self::assertResponseHeaderSame('Content-Type', 'application/problem+json');
        self::assertSame(400, $this->body()['status']);
    }

    public function testUnknownModuleIs404(): void
    {
        $this->authenticateAsUser($this->getFixture('test_user'));

        $this->client->request('GET', '/api/home/finance', [], [], $this->authHeaders());

        self::assertResponseStatusCodeSame(404);
        self::assertSame(404, $this->body()['status']);
    }

    public function testDefaultsToToday(): void
    {
        $user = $this->getFixture('test_user');
        $this->authenticateAsUser($user);

        $this->client->request('GET', '/api/home/leisure', [], [], $this->authHeaders());

        self::assertResponseIsSuccessful();
        self::assertSame(
            ['module' => 'leisure', 'day' => 'today', 'userId' => (string) $user->getId(), 'date' => '2026-10-14'],
            $this->body(),
        );
    }

    public function testTomorrow(): void
    {
        $user = $this->getFixture('test_user');
        $this->authenticateAsUser($user);

        $this->client->request('GET', '/api/home/leisure?day=tomorrow', [], [], $this->authHeaders());

        self::assertResponseIsSuccessful();
        self::assertSame(
            ['module' => 'leisure', 'day' => 'tomorrow', 'userId' => (string) $user->getId(), 'date' => '2026-10-15'],
            $this->body(),
        );
    }

    public function testProviderGetsTheAuthenticatedUserOnly(): void
    {
        $other = $this->getFixture('other_user');
        $this->authenticateAsUser($other);

        $this->client->request('GET', '/api/home/leisure', [], [], $this->authHeaders());

        self::assertSame((string) $other->getId(), $this->body()['userId']);
        self::assertNotSame((string) $this->getFixture('test_user')->getId(), $this->body()['userId']);
    }
}
