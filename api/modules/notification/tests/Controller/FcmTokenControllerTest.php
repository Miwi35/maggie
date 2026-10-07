<?php

declare(strict_types=1);

namespace Maggie\Notification\Tests\Controller;

use App\Tests\Support\AuthenticatedTestTrait;
use App\Tests\Support\FixtureLoaderTrait;
use App\Tests\Support\MercureAssertionTrait;
use Doctrine\ORM\EntityManagerInterface;
use Maggie\Notification\Entity\DeviceToken;
use Maggie\Notification\Enum\DevicePlatform;
use PHPUnit\Framework\Attributes\DataProvider;
use Symfony\Bundle\FrameworkBundle\KernelBrowser;
use Symfony\Bundle\FrameworkBundle\Test\WebTestCase;

/**
 * Where the app registers the FCM token of its device (MAG-26). The token goes
 * in and never comes back out: no Mercure update carries it.
 */
final class FcmTokenControllerTest extends WebTestCase
{
    use AuthenticatedTestTrait;
    use FixtureLoaderTrait;
    use MercureAssertionTrait;

    private KernelBrowser $client;

    protected function setUp(): void
    {
        $this->client = self::createClient();
        $this->resetMercure();
        $this->loadFixtures('device_tokens.yaml');
    }

    public function testRegisteringUnauthenticatedReturns401(): void
    {
        $this->client->request('POST', '/api/fcm_tokens', [], [], ['CONTENT_TYPE' => 'application/json'], '{"token":"abc"}');

        self::assertResponseStatusCodeSame(401);
        self::assertNull($this->stored('abc'));
    }

    public function testUnregisteringUnauthenticatedReturns401(): void
    {
        $this->client->request('DELETE', '/api/fcm_tokens', [], [], ['CONTENT_TYPE' => 'application/json'], '{"token":"own-token"}');

        self::assertResponseStatusCodeSame(401);
        self::assertNotNull($this->stored('own-token'));
    }

    /** @return iterable<string, array{string}> */
    public static function badBodies(): iterable
    {
        yield 'not JSON' => ['token=abc'];
        yield 'a list' => ['["abc"]'];
        yield 'no token' => ['{"deviceName":"Pixel"}'];
        yield 'blank token' => ['{"token":"   "}'];
        yield 'token not a string' => ['{"token":42}'];
        yield 'token too long' => [json_encode(['token' => str_repeat('a', 513)], JSON_THROW_ON_ERROR)];
        yield 'unknown platform' => ['{"token":"abc","platform":"symbian"}'];
        yield 'device name not a string' => ['{"token":"abc","deviceName":["Pixel"]}'];
    }

    #[DataProvider('badBodies')]
    public function testRegisteringABadBodyReturns400(string $body): void
    {
        $this->authenticateAsUser($this->getFixture('test_user'));

        $this->send('POST', $body);

        self::assertResponseStatusCodeSame(400);
        self::assertNull($this->stored('abc'));
    }

    public function testUnregisteringWithoutATokenReturns400(): void
    {
        $this->authenticateAsUser($this->getFixture('test_user'));

        $this->send('DELETE', '{}');

        self::assertResponseStatusCodeSame(400);
    }

    public function testRegisteringStoresTheDeviceAsTheMobileSendsIt(): void
    {
        $this->authenticateAsUser($this->getFixture('test_user'));

        // Exactly what MaggieApiService.registerFcmToken posts.
        $this->send('POST', '{"token":"new-token","deviceName":"Pixel 9"}');

        self::assertResponseStatusCodeSame(204);
        $stored = $this->stored('new-token');
        self::assertNotNull($stored);
        self::assertSame('fcm-test@example.com', $stored->getUser()->getEmail());
        self::assertSame(DevicePlatform::Android, $stored->getPlatform());
        self::assertSame('Pixel 9', $stored->getDeviceName());
        $this->assertMercureUpdateCount(0);
    }

    public function testRegisteringAKnownTokenAgainRefreshesItWithoutADuplicate(): void
    {
        $this->authenticateAsUser($this->getFixture('test_user'));

        $this->send('POST', '{"token":"own-token","platform":"android","deviceName":"Pixel 8a"}');

        self::assertResponseStatusCodeSame(204);
        self::assertCount(1, $this->em()->getRepository(DeviceToken::class)->findBy(['token' => 'own-token']));
        $stored = $this->stored('own-token');
        self::assertSame('Pixel 8a', $stored?->getDeviceName());
        self::assertGreaterThan(new \DateTimeImmutable('2026-01-02'), $stored?->getLastSeenAt());
    }

    public function testATokenRegisteredByAnotherAccountMovesToTheNewOne(): void
    {
        $this->authenticateAsUser($this->getFixture('test_user'));

        $this->send('POST', '{"token":"other-token"}');

        self::assertResponseStatusCodeSame(204);
        self::assertSame('fcm-test@example.com', $this->stored('other-token')?->getUser()->getEmail());
    }

    public function testUnregisteringRemovesTheDevice(): void
    {
        $this->authenticateAsUser($this->getFixture('test_user'));

        $this->send('DELETE', '{"token":"own-token"}');

        self::assertResponseStatusCodeSame(204);
        self::assertNull($this->stored('own-token'));
        $this->assertMercureUpdateCount(0);
    }

    public function testUnregisteringLeavesAnotherUsersDeviceAlone(): void
    {
        $this->authenticateAsUser($this->getFixture('test_user'));

        $this->send('DELETE', '{"token":"other-token"}');

        // Same answer as for an unknown token: a logout must not fail, and it
        // must not tell whether a token exists.
        self::assertResponseStatusCodeSame(204);
        self::assertNotNull($this->stored('other-token'));
    }

    private function send(string $method, string $body): void
    {
        $this->client->request($method, '/api/fcm_tokens', [], [], array_merge(
            ['CONTENT_TYPE' => 'application/json'],
            $this->authHeaders(),
        ), $body);
    }

    private function stored(string $token): ?DeviceToken
    {
        $em = $this->em();
        $em->clear();

        return $em->getRepository(DeviceToken::class)->findOneBy(['token' => $token]);
    }

    private function em(): EntityManagerInterface
    {
        return self::getContainer()->get('doctrine.orm.entity_manager');
    }
}
