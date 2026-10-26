<?php

declare(strict_types=1);

namespace Maggie\Notification\Tests\Push;

use App\Tests\Support\FixtureLoaderTrait;
use Doctrine\ORM\EntityManagerInterface;
use Maggie\Notification\Entity\DeviceToken;
use Maggie\Notification\Push\FcmClient;
use Maggie\Notification\Push\LocationRequester;
use Maggie\Notification\Push\PushMessageFactory;
use Maggie\Notification\Repository\DeviceTokenRepository;
use Maggie\Notification\UseCase\UnregisterDeviceToken;
use Psr\Log\NullLogger;
use Symfony\Bundle\FrameworkBundle\Test\KernelTestCase;
use Symfony\Component\Cache\Adapter\ArrayAdapter;
use Symfony\Component\HttpClient\MockHttpClient;
use Symfony\Component\HttpClient\Response\MockResponse;

/**
 * A position too old to plan a departure with: every phone of the user is
 * asked for a fresh one (MAG-650), silently. FCM is mocked.
 */
final class LocationRequesterTest extends KernelTestCase
{
    use FixtureLoaderTrait;

    private string $keyPath;

    /** @var array<string, int> FCM answer per device token; 200 when absent */
    private array $answers = [];

    /** @var list<array<string, mixed>> the messages FCM received */
    private array $sent = [];

    protected function setUp(): void
    {
        self::bootKernel();
        $this->loadFixtures('location_request.yaml');

        $key = openssl_pkey_new(['private_key_bits' => 2048, 'private_key_type' => OPENSSL_KEYTYPE_RSA]);
        openssl_pkey_export($key, $pem);
        $this->keyPath = (string) tempnam(sys_get_temp_dir(), 'fcm-key-');
        file_put_contents($this->keyPath, json_encode([
            'project_id' => 'maggie-test',
            'client_email' => 'push@maggie-test.iam.gserviceaccount.com',
            'private_key' => $pem,
        ], JSON_THROW_ON_ERROR));
    }

    protected function tearDown(): void
    {
        @unlink($this->keyPath);
        parent::tearDown();
    }

    public function testEveryDeviceOfTheUserIsAskedSilentlyAndUrgently(): void
    {
        $reached = $this->requester()->request($this->getFixture('driver'));

        self::assertSame(2, $reached);
        self::assertEqualsCanonicalizing(['phone-token', 'tablet-token'], array_column($this->sent, 'token'));
        foreach ($this->sent as $message) {
            self::assertArrayNotHasKey('notification', $message);
            self::assertSame(['type' => 'location_request'], $message['data']);
            self::assertSame('high', $message['android']['priority']);
        }
    }

    public function testATokenGoogleNoLongerKnowsIsForgotten(): void
    {
        $this->answers['tablet-token'] = 404;

        self::assertSame(1, $this->requester()->request($this->getFixture('driver')));

        self::assertNotNull($this->storedToken('phone-token'));
        self::assertNull($this->storedToken('tablet-token'));
    }

    public function testAnOutageIsSwallowedAndTheOtherDevicesStillAsked(): void
    {
        $this->answers['phone-token'] = 503;

        self::assertSame(1, $this->requester()->request($this->getFixture('driver')));

        self::assertEqualsCanonicalizing(['phone-token', 'tablet-token'], array_column($this->sent, 'token'));
        self::assertNotNull($this->storedToken('phone-token'));
    }

    public function testWithoutAKeyNothingIsSentAndNothingFails(): void
    {
        self::assertSame(0, $this->requester(configured: false)->request($this->getFixture('driver')));

        self::assertSame([], $this->sent);
    }

    private function requester(bool $configured = true): LocationRequester
    {
        $container = self::getContainer();

        return new LocationRequester(
            $this->fcm($configured),
            new PushMessageFactory(),
            $container->get(DeviceTokenRepository::class),
            $container->get(UnregisterDeviceToken::class),
            new NullLogger(),
        );
    }

    /** FCM, mocked: every message lands in `$sent`, answered as `$answers` says. */
    private function fcm(bool $configured): FcmClient
    {
        $http = new MockHttpClient(function (string $method, string $url, array $options) {
            if (str_contains($url, 'oauth2')) {
                return new MockResponse(json_encode(['access_token' => 'tok', 'expires_in' => 3599]));
            }

            $message = json_decode($options['body'], true, 512, JSON_THROW_ON_ERROR)['message'];
            $this->sent[] = $message;
            $status = $this->answers[$message['token']] ?? 200;

            $error = ['code' => $status];
            if (404 === $status) {
                $error['details'] = [['errorCode' => 'UNREGISTERED']];
            }

            return new MockResponse(json_encode(200 === $status ? ['name' => 'm'] : ['error' => $error]), ['http_code' => $status]);
        });

        return new FcmClient($http, new ArrayAdapter(), new NullLogger(), $configured ? $this->keyPath : '');
    }

    private function storedToken(string $token): ?DeviceToken
    {
        $em = self::getContainer()->get('doctrine.orm.entity_manager');
        \assert($em instanceof EntityManagerInterface);
        $em->clear();

        return $em->getRepository(DeviceToken::class)->findOneBy(['token' => $token]);
    }
}
