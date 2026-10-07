<?php

declare(strict_types=1);

namespace Maggie\Notification\Tests\MessageHandler;

use App\Tests\Support\ElasticsearchAssertionTrait;
use App\Tests\Support\FixtureLoaderTrait;
use App\Tests\Support\MercureAssertionTrait;
use Doctrine\ORM\EntityManagerInterface;
use Maggie\Core\Entity\User;
use Maggie\Notification\Entity\DeviceToken;
use Maggie\Notification\Entity\Notification;
use Maggie\Notification\Message\CreateNotificationCommand;
use Maggie\Notification\Message\SendPushNotificationCommand;
use Maggie\Notification\MessageHandler\SendPushNotificationHandler;
use Maggie\Notification\Push\FcmClient;
use Maggie\Notification\Push\FcmUnavailableException;
use Maggie\Notification\Push\PushMessageFactory;
use Maggie\Notification\Repository\DeviceTokenRepository;
use Maggie\Notification\Repository\NotificationRepository;
use Maggie\Notification\UseCase\UnregisterDeviceToken;
use Psr\Log\NullLogger;
use Symfony\Bundle\FrameworkBundle\Test\KernelTestCase;
use Symfony\Component\Cache\Adapter\ArrayAdapter;
use Symfony\Component\HttpClient\MockHttpClient;
use Symfony\Component\HttpClient\Response\MockResponse;
use Symfony\Component\Messenger\MessageBusInterface;

/**
 * Every notification reaches the user's devices (MAG-26): queued when it is
 * created, sent by the worker to each registered token. FCM is mocked.
 */
final class SendPushNotificationHandlerTest extends KernelTestCase
{
    use ElasticsearchAssertionTrait;
    use FixtureLoaderTrait;
    use MercureAssertionTrait;

    private string $keyPath;

    /** @var array<string, int> FCM answer per device token; 200 when absent */
    private array $answers = [];

    /** @var list<array<string, mixed>> the messages FCM received */
    private array $sent = [];

    protected function setUp(): void
    {
        self::bootKernel();
        $this->resetMercure();
        $this->resetAsyncTransport();
        $this->loadFixtures('push.yaml');

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

    public function testCreatingANotificationQueuesItsPush(): void
    {
        $user = $this->getFixture('push_user');
        \assert($user instanceof User);

        self::getContainer()->get(MessageBusInterface::class)->dispatch(new CreateNotificationCommand(
            type: 'proaction',
            title: 'Nouvelle proaction',
            userId: (string) $user->getId(),
        ));

        $queued = array_values(array_filter(
            array_map(static fn ($e) => $e->getMessage(), $this->getAsyncTransport()->getSent()),
            static fn (object $m) => $m instanceof SendPushNotificationCommand,
        ));
        self::assertCount(1, $queued);
        $created = $this->em()->getRepository(Notification::class)->findOneBy(['title' => 'Nouvelle proaction']);
        self::assertSame((string) $created?->getId(), $queued[0]->notificationId);
        $this->assertMercureUpdatePublished('/api/notifications/');
        $this->assertElasticsearchIndexDispatched(Notification::class);
    }

    public function testEveryDeviceOfTheUserGetsThePush(): void
    {
        $this->handle('unread');

        self::assertEqualsCanonicalizing(['phone-token', 'tablet-token'], array_column($this->sent, 'token'));
        self::assertSame('J\'ai déplacé ton rendez-vous', $this->sent[0]['notification']['title']);
        self::assertSame((string) $this->getFixture('unread')->getId(), $this->sent[0]['data']['notificationId']);
        self::assertSame('maggie://event/01JEVENT', $this->sent[0]['data']['link']);
    }

    public function testATokenGoogleNoLongerKnowsIsForgotten(): void
    {
        $this->answers['tablet-token'] = 404;

        $this->handle('unread');

        self::assertNotNull($this->storedToken('phone-token'));
        self::assertNull($this->storedToken('tablet-token'));
    }

    public function testAnOutageOnOneDeviceStillServesTheOthersThenAsksForARetry(): void
    {
        $this->answers['phone-token'] = 503;

        try {
            $this->handle('unread');
            self::fail('The worker must retry a push FCM could not take.');
        } catch (FcmUnavailableException) {
        }

        self::assertEqualsCanonicalizing(['phone-token', 'tablet-token'], array_column($this->sent, 'token'));
        self::assertNotNull($this->storedToken('phone-token'));
    }

    public function testANotificationAlreadyReadIsNotPushed(): void
    {
        $this->handle('already_read');

        self::assertSame([], $this->sent);
    }

    public function testADeletedNotificationIsNotPushed(): void
    {
        $this->handler()(new SendPushNotificationCommand('01JZZZZZZZZZZZZZZZZZZZZZZZ'));

        self::assertSame([], $this->sent);
    }

    public function testWithoutAKeyNothingIsSentAndNothingFails(): void
    {
        $this->handle('unread', configured: false);

        self::assertSame([], $this->sent);
        self::assertNotNull($this->storedToken('phone-token'));
    }

    private function handle(string $notificationRef, bool $configured = true): void
    {
        $this->handler($configured)(new SendPushNotificationCommand((string) $this->getFixture($notificationRef)->getId()));
    }

    private function handler(bool $configured = true): SendPushNotificationHandler
    {
        $http = new MockHttpClient(function (string $method, string $url, array $options) {
            if (str_contains($url, 'oauth2')) {
                return new MockResponse(json_encode(['access_token' => 'tok', 'expires_in' => 3599]));
            }

            $message = json_decode($options['body'], true, 512, JSON_THROW_ON_ERROR)['message'];
            $this->sent[] = $message;
            $status = $this->answers[$message['token']] ?? 200;

            return new MockResponse(json_encode(200 === $status ? ['name' => 'm'] : ['error' => ['code' => $status]]), ['http_code' => $status]);
        });

        $container = self::getContainer();

        return new SendPushNotificationHandler(
            new FcmClient($http, new ArrayAdapter(), new NullLogger(), $configured ? $this->keyPath : ''),
            new PushMessageFactory(),
            $container->get(NotificationRepository::class),
            $container->get(DeviceTokenRepository::class),
            $container->get(UnregisterDeviceToken::class),
            new NullLogger(),
        );
    }

    private function storedToken(string $token): ?DeviceToken
    {
        $this->em()->clear();

        return $this->em()->getRepository(DeviceToken::class)->findOneBy(['token' => $token]);
    }

    private function em(): EntityManagerInterface
    {
        return self::getContainer()->get('doctrine.orm.entity_manager');
    }
}
