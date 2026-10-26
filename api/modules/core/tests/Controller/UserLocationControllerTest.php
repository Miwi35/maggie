<?php

declare(strict_types=1);

namespace Maggie\Core\Tests\Controller;

use App\Tests\Support\AuthenticatedTestTrait;
use App\Tests\Support\FixtureLoaderTrait;
use Doctrine\ORM\EntityManagerInterface;
use Maggie\Core\Entity\User;
use Maggie\Core\Entity\UserLocation;
use Maggie\Core\Event\UserLocationChangedEvent;
use PHPUnit\Framework\Attributes\DataProvider;
use Symfony\Bundle\FrameworkBundle\KernelBrowser;
use Symfony\Bundle\FrameworkBundle\Test\WebTestCase;
use Symfony\Component\Messenger\Envelope;
use Symfony\Component\Messenger\MessageBusInterface;

/**
 * The phone reports where the owner is and where the car is (MAG-650): one row
 * per user, overwritten, never a history, and an event when a value moves.
 */
final class UserLocationControllerTest extends WebTestCase
{
    use FixtureLoaderTrait;
    use AuthenticatedTestTrait;

    private const POSITION = ['latitude' => 48.8566, 'longitude' => 2.3522, 'accuracy' => 12.5, 'capturedAt' => '2026-10-26T08:15:00+01:00'];
    private const CAR = ['latitude' => 48.85, 'longitude' => 2.35, 'parkedAt' => '2026-10-26T07:40:00Z'];

    private KernelBrowser $client;

    /** @var list<object> what reached the event bus */
    private array $dispatched = [];

    protected function setUp(): void
    {
        $this->client = self::createClient();
        // One kernel for the whole test, so the spy below sees every request's events.
        $this->client->disableReboot();
        // Before anything flushes: the listener takes its bus when Doctrine first needs it.
        self::getContainer()->set('event.bus', $this->spyBus());
        $this->loadFixtures('user_location.yaml');
        $this->dispatched = [];
    }

    private function spyBus(): MessageBusInterface
    {
        return new class($this->dispatched) implements MessageBusInterface {
            /** @param list<object> $dispatched */
            public function __construct(private array &$dispatched)
            {
            }

            public function dispatch(object $message, array $stamps = []): Envelope
            {
                $this->dispatched[] = $message;

                return new Envelope($message);
            }
        };
    }

    /** @param array<string, mixed>|string $body */
    private function put(string $path, array|string $body, bool $authenticated = true): void
    {
        $this->client->request('PUT', $path, [], [], array_merge(
            ['CONTENT_TYPE' => 'application/json'],
            $authenticated ? $this->authHeaders() : [],
        ), \is_string($body) ? $body : json_encode($body, JSON_THROW_ON_ERROR));
    }

    public static function endpoints(): iterable
    {
        yield 'location' => ['/api/me/location', self::POSITION];
        yield 'car' => ['/api/me/car', self::CAR];
    }

    /** @param array<string, mixed> $body */
    #[DataProvider('endpoints')]
    public function testUnauthenticatedReturns401(string $path, array $body): void
    {
        $this->put($path, $body, authenticated: false);

        self::assertResponseStatusCodeSame(401);
        self::assertNull($this->locationOf('test_user'));
    }

    public static function invalidBodies(): iterable
    {
        yield 'latitude above 90' => ['/api/me/location', ['latitude' => 90.1] + self::POSITION, 'latitude'];
        yield 'latitude below -90' => ['/api/me/location', ['latitude' => -91] + self::POSITION, 'latitude'];
        yield 'longitude above 180' => ['/api/me/location', ['longitude' => 180.5] + self::POSITION, 'longitude'];
        yield 'longitude as a string' => ['/api/me/location', ['longitude' => '2.35'] + self::POSITION, 'longitude'];
        yield 'negative accuracy' => ['/api/me/location', ['accuracy' => -1] + self::POSITION, 'accuracy'];
        yield 'accuracy missing' => ['/api/me/location', array_diff_key(self::POSITION, ['accuracy' => true]), 'accuracy'];
        yield 'capturedAt missing' => ['/api/me/location', array_diff_key(self::POSITION, ['capturedAt' => true]), 'capturedAt'];
        yield 'capturedAt not a date' => ['/api/me/location', ['capturedAt' => 'hier'] + self::POSITION, 'capturedAt'];
        yield 'capturedAt without offset' => ['/api/me/location', ['capturedAt' => '2026-10-26T08:15:00'] + self::POSITION, 'capturedAt'];
        yield 'capturedAt an impossible day' => ['/api/me/location', ['capturedAt' => '2026-02-30T08:15:00Z'] + self::POSITION, 'capturedAt'];
        yield 'car latitude out of range' => ['/api/me/car', ['latitude' => 100] + self::CAR, 'latitude'];
        yield 'car longitude out of range' => ['/api/me/car', ['longitude' => -181] + self::CAR, 'longitude'];
        yield 'car longitude missing' => ['/api/me/car', array_diff_key(self::CAR, ['longitude' => true]), 'longitude'];
        yield 'parkedAt invalid' => ['/api/me/car', ['parkedAt' => 1730000000] + self::CAR, 'parkedAt'];
    }

    /** @param array<string, mixed> $body */
    #[DataProvider('invalidBodies')]
    public function testAnInvalidFieldIsRejectedAndNamed(string $path, array $body, string $field): void
    {
        $this->authenticateAsUser($this->getFixture('test_user'));

        $this->put($path, $body);

        self::assertResponseStatusCodeSame(400);
        self::assertSame($field, $this->json()['field']);
        self::assertNull($this->locationOf('test_user'));
        self::assertSame([], $this->events());
    }

    public function testABodyThatIsNotAJsonObjectIsRejected(): void
    {
        $this->authenticateAsUser($this->getFixture('test_user'));

        $this->put('/api/me/location', '[1, 2]');
        self::assertResponseStatusCodeSame(400);

        $this->put('/api/me/location', '{nope');
        self::assertResponseStatusCodeSame(400);
    }

    public function testTheFirstReportCreatesTheRowWithTheValuesSent(): void
    {
        $this->authenticateAsUser($this->getFixture('test_user'));

        $this->put('/api/me/location', self::POSITION);

        self::assertResponseStatusCodeSame(204);
        $location = $this->locationOf('test_user');
        self::assertNotNull($location);
        self::assertSame(48.8566, $location->getLatitude());
        self::assertSame(2.3522, $location->getLongitude());
        self::assertSame(12.5, $location->getAccuracy());
        self::assertSame((new \DateTimeImmutable('2026-10-26T07:15:00Z'))->getTimestamp(), $location->getCapturedAt()?->getTimestamp());
        self::assertNull($location->getCarLatitude());
        self::assertEquals([new UserLocationChangedEvent($this->userId('test_user'), 'position')], $this->events());
    }

    public function testASecondReportOverwritesTheOnlyRow(): void
    {
        $this->authenticateAsUser($this->getFixture('test_user'));

        $this->put('/api/me/location', self::POSITION);
        $this->put('/api/me/location', ['latitude' => 43.6, 'longitude' => 1.44, 'accuracy' => 5, 'capturedAt' => '2026-10-26T09:00:00Z']);

        self::assertResponseStatusCodeSame(204);
        self::assertSame(1, $this->rowsOf('test_user'));
        $location = $this->locationOf('test_user');
        self::assertSame(43.6, $location?->getLatitude());
        self::assertSame(1.44, $location->getLongitude());
        self::assertSame(5.0, $location->getAccuracy());
        self::assertSame('2026-10-26T09:00:00+00:00', $location->getCapturedAt()?->setTimezone(new \DateTimeZone('UTC'))->format(DATE_ATOM));
        self::assertCount(2, $this->events());
    }

    public function testTheSameReportSentAgainRaisesNoEvent(): void
    {
        $this->authenticateAsUser($this->getFixture('test_user'));

        $this->put('/api/me/location', self::POSITION);
        $this->put('/api/me/location', self::POSITION);

        self::assertResponseStatusCodeSame(204);
        self::assertCount(1, $this->events());
    }

    public function testParkingTheCarLeavesThePositionAlone(): void
    {
        $this->authenticateAsUser($this->getFixture('test_user'));
        $this->put('/api/me/location', self::POSITION);

        $this->put('/api/me/car', self::CAR);

        self::assertResponseStatusCodeSame(204);
        $location = $this->locationOf('test_user');
        self::assertSame(48.85, $location?->getCarLatitude());
        self::assertSame(2.35, $location->getCarLongitude());
        self::assertSame((new \DateTimeImmutable('2026-10-26T07:40:00Z'))->getTimestamp(), $location->getCarParkedAt()?->getTimestamp());
        self::assertSame(48.8566, $location->getLatitude());
        self::assertSame(12.5, $location->getAccuracy());
        self::assertEquals([
            new UserLocationChangedEvent($this->userId('test_user'), 'position'),
            new UserLocationChangedEvent($this->userId('test_user'), 'car'),
        ], $this->events());
    }

    public function testTheCarCanComeBeforeAnyPosition(): void
    {
        $this->authenticateAsUser($this->getFixture('test_user'));

        $this->put('/api/me/car', self::CAR);

        self::assertResponseStatusCodeSame(204);
        $location = $this->locationOf('test_user');
        self::assertSame(48.85, $location?->getCarLatitude());
        self::assertNull($location->getLatitude());
        self::assertEquals([new UserLocationChangedEvent($this->userId('test_user'), 'car')], $this->events());
    }

    public function testAUserNeverTouchesAnotherUsersRow(): void
    {
        $this->authenticateAsUser($this->getFixture('test_user'));

        $this->put('/api/me/location', self::POSITION);
        $this->put('/api/me/car', self::CAR);

        self::assertSame(1, $this->rowsOf('test_user'));
        $other = $this->locationOf('other_user');
        self::assertSame(45.75, $other?->getLatitude());
        self::assertSame(4.85, $other->getLongitude());
        self::assertSame(45.76, $other->getCarLatitude());
        self::assertNotContains($this->userId('other_user'), array_map(static fn (UserLocationChangedEvent $e) => $e->userId, $this->events()));
    }

    /** @return array<string, mixed> */
    private function json(): array
    {
        return json_decode((string) $this->client->getResponse()->getContent(), true, 512, JSON_THROW_ON_ERROR);
    }

    /** @return list<UserLocationChangedEvent> */
    private function events(): array
    {
        return array_values(array_filter(
            $this->dispatched,
            static fn (object $message) => $message instanceof UserLocationChangedEvent,
        ));
    }

    private function userId(string $ref): string
    {
        $user = $this->getFixture($ref);
        \assert($user instanceof User);

        return (string) $user->getId();
    }

    private function locationOf(string $userRef): ?UserLocation
    {
        $this->em()->clear();

        return $this->em()->getRepository(UserLocation::class)->findOneBy(['user' => $this->getFixture($userRef)->getId()]);
    }

    private function rowsOf(string $userRef): int
    {
        return \count($this->em()->getRepository(UserLocation::class)->findBy(['user' => $this->getFixture($userRef)->getId()]));
    }

    private function em(): EntityManagerInterface
    {
        return self::getContainer()->get('doctrine.orm.entity_manager');
    }
}
