<?php

declare(strict_types=1);

namespace Maggie\Notification\Tests\MessageHandler;

use App\Tests\Support\ElasticsearchAssertionTrait;
use App\Tests\Support\FixtureLoaderTrait;
use App\Tests\Support\MercureAssertionTrait;
use Doctrine\ORM\EntityManagerInterface;
use Maggie\Core\Entity\User;
use Maggie\Core\Repository\UserPreferenceRepository;
use Maggie\Notification\Entity\DeviceToken;
use Maggie\Notification\Entity\Notification;
use Maggie\Notification\Message\CreateNotificationCommand;
use Maggie\Notification\Message\SendPushNotificationCommand;
use Maggie\Notification\Message\WithdrawPushNotificationCommand;
use Maggie\Notification\MessageHandler\SendPushNotificationHandler;
use Maggie\Notification\MessageHandler\WithdrawPushNotificationHandler;
use Maggie\Notification\Push\FcmClient;
use Maggie\Notification\Push\FcmUnavailableException;
use Maggie\Notification\Push\PushMessageFactory;
use Maggie\Notification\Push\QuietHours;
use Maggie\Notification\Repository\DeviceTokenRepository;
use Maggie\Notification\Repository\NotificationRepository;
use Maggie\Notification\UseCase\UnregisterDeviceToken;
use PHPUnit\Framework\Attributes\DataProvider;
use Psr\Log\NullLogger;
use Symfony\Bundle\FrameworkBundle\Test\KernelTestCase;
use Symfony\Component\Cache\Adapter\ArrayAdapter;
use Symfony\Component\Clock\MockClock;
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

    public function testNoPushIsQueuedForAUserWhoTurnedNotificationsOff(): void
    {
        $user = $this->getFixture('silent_user');
        \assert($user instanceof User);

        self::getContainer()->get(MessageBusInterface::class)->dispatch(new CreateNotificationCommand(
            type: 'proaction',
            title: 'Muette',
            userId: (string) $user->getId(),
        ));

        $pushes = array_filter(
            $this->getAsyncTransport()->getSent(),
            static fn ($envelope) => $envelope->getMessage() instanceof SendPushNotificationCommand,
        );
        self::assertSame([], $pushes);
    }

    public function testEveryDeviceOfTheUserGetsThePush(): void
    {
        $this->handle('unread');

        self::assertEqualsCanonicalizing(['phone-token', 'tablet-token'], array_column($this->sent, 'token'));
        self::assertSame('J\'ai déplacé ton rendez-vous', $this->sent[0]['data']['title']);
        self::assertSame((string) $this->getFixture('unread')->getId(), $this->sent[0]['data']['notificationId']);
        self::assertSame('maggie://event/01JEVENT', $this->sent[0]['data']['link']);
    }

    /**
     * Quiet hours (MAG-687): 22:00-07:00 in Europe/Paris for `night_message`, 13:00-14:00 for `lunch_message`.
     *
     * @return iterable<string, array{string, string, bool}>
     */
    public static function quietHoursCases(): iterable
    {
        // Paris is UTC+1 on 1 March 2026.
        yield 'inside, before midnight' => ['night_message', '2026-03-01 22:30:00 UTC', true]; // 23:30 Paris
        yield 'inside, after midnight' => ['night_message', '2026-03-01 03:00:00 UTC', true]; // 04:00 Paris
        yield 'start included' => ['night_message', '2026-03-01 21:00:00 UTC', true]; // 22:00 Paris
        yield 'end excluded' => ['night_message', '2026-03-01 06:00:00 UTC', false]; // 07:00 Paris
        yield 'outside' => ['night_message', '2026-03-01 11:00:00 UTC', false]; // 12:00 Paris
        yield 'timezone, not UTC, decides' => ['night_message', '2026-03-01 22:30:00 UTC', true];
        yield 'same-day window, inside' => ['lunch_message', '2026-03-01 12:30:00 UTC', true]; // 13:30 Paris
        yield 'same-day window, outside' => ['lunch_message', '2026-03-01 14:30:00 UTC', false]; // 15:30 Paris
        yield 'no quiet hours set' => ['open_message', '2026-03-01 23:30:00 UTC', false];
        yield 'another type is never silent' => ['night_proaction', '2026-03-01 22:30:00 UTC', false];
    }

    #[DataProvider('quietHoursCases')]
    public function testAChatMessageIsSilentOnlyInsideTheQuietHours(string $notification, string $now, bool $silent): void
    {
        $this->handle($notification, clock: new MockClock($now));

        self::assertCount(1, $this->sent);
        if ($silent) {
            self::assertSame('1', $this->sent[0]['data']['silent']);
        } else {
            self::assertArrayNotHasKey('silent', $this->sent[0]['data']);
        }
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

    /** A notification seen elsewhere leaves every device's tray (MAG-582): one data-only message per device. */
    public function testAWithdrawalReachesEveryDeviceAsDataOnly(): void
    {
        $notification = $this->getFixture('already_read');
        $this->withdrawalHandler()(new WithdrawPushNotificationCommand((string) $notification->getId()));

        self::assertEqualsCanonicalizing(['phone-token', 'tablet-token'], array_column($this->sent, 'token'));
        self::assertArrayNotHasKey('notification', $this->sent[0]);
        self::assertSame('withdraw', $this->sent[0]['data']['type']);
        self::assertSame((string) $notification->getId(), $this->sent[0]['data']['tag']);
    }

    public function testNoWithdrawalForADeletedNotificationOrWithoutAKey(): void
    {
        $this->withdrawalHandler()(new WithdrawPushNotificationCommand('01JZZZZZZZZZZZZZZZZZZZZZZZ'));
        $this->withdrawalHandler(configured: false)(new WithdrawPushNotificationCommand((string) $this->getFixture('already_read')->getId()));

        self::assertSame([], $this->sent);
    }

    private function withdrawalHandler(bool $configured = true): WithdrawPushNotificationHandler
    {
        $container = self::getContainer();

        return new WithdrawPushNotificationHandler(
            $this->fcm($configured),
            new PushMessageFactory(),
            $container->get(NotificationRepository::class),
            $container->get(DeviceTokenRepository::class),
            $container->get(UnregisterDeviceToken::class),
            new NullLogger(),
        );
    }

    private function handle(string $notificationRef, bool $configured = true, ?MockClock $clock = null): void
    {
        $this->handler($configured, $clock)(new SendPushNotificationCommand((string) $this->getFixture($notificationRef)->getId()));
    }

    private function handler(bool $configured = true, ?MockClock $clock = null): SendPushNotificationHandler
    {
        $container = self::getContainer();

        return new SendPushNotificationHandler(
            $this->fcm($configured),
            new PushMessageFactory(),
            $container->get(NotificationRepository::class),
            $container->get(DeviceTokenRepository::class),
            $container->get(UnregisterDeviceToken::class),
            new QuietHours($container->get(UserPreferenceRepository::class), $clock ?? new MockClock('2026-03-01 12:00:00 UTC')),
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
        $this->em()->clear();

        return $this->em()->getRepository(DeviceToken::class)->findOneBy(['token' => $token]);
    }

    private function em(): EntityManagerInterface
    {
        return self::getContainer()->get('doctrine.orm.entity_manager');
    }
}
