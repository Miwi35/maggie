<?php

declare(strict_types=1);

namespace Maggie\Core\Tests\Home;

use App\Tests\Support\ElasticsearchAssertionTrait;
use App\Tests\Support\FixtureLoaderTrait;
use App\Tests\Support\MercureAssertionTrait;
use Maggie\Core\Entity\User;
use Maggie\Core\Home\HomeCardProviderInterface;
use Maggie\Core\Home\HomeCardPublisher;
use Maggie\Core\Home\HomeCardWatcherInterface;
use Maggie\Core\Home\HomeDay;
use Maggie\Core\Projection\Change;
use Maggie\Core\Projection\ChangeKind;
use Symfony\Bundle\FrameworkBundle\Test\KernelTestCase;

class HomeCardPublisherTest extends KernelTestCase
{
    use FixtureLoaderTrait;
    use ElasticsearchAssertionTrait;
    use MercureAssertionTrait;

    protected function setUp(): void
    {
        self::bootKernel();
        $this->resetMercure();
        $this->resetAsyncTransport();
        $this->loadFixtures('HomeControllerTest.yaml');
    }

    public function testPublishesAPrivateRefreshOnTheUsersHomeTopic(): void
    {
        $user = $this->getFixture('test_user');

        (new HomeCardPublisher($this->getMercureHub()))->publish('tasks', $user);

        $topic = '/users/'.$user->getId().'/home/tasks';
        $this->assertMercureUpdatePublished($topic);
        self::assertCount(1, $this->homeRefreshes());
        $update = $this->getMercureHub()->getUpdates()[0];
        self::assertSame([$topic], $update->getTopics());
        self::assertTrue($update->isPrivate());
        self::assertSame(['action' => 'refresh', 'module' => 'tasks'], json_decode($update->getData(), true));
    }

    public function testEachRefreshIsLoggedWithItsTopicAndTypeAndTheMessageClass(): void
    {
        $logger = new class extends \Psr\Log\AbstractLogger {
            /** @var list<array{level: mixed, message: string, context: array<string, mixed>}> */
            public array $records = [];

            public function log($level, string|\Stringable $message, array $context = []): void
            {
                $this->records[] = ['level' => $level, 'message' => (string) $message, 'context' => $context];
            }
        };
        $publisher = new HomeCardPublisher($this->getMercureHub(), [$this->watcher('shelf', [\ArrayObject::class])], $logger);

        $publisher->publishAffected([new Change(\ArrayObject::class, '1', ChangeKind::Inserted, 'owner-a')], 'App\\Some\\Command');

        self::assertCount(1, $logger->records);
        self::assertSame(\Psr\Log\LogLevel::INFO, $logger->records[0]['level']);
        self::assertSame(
            ['topic' => '/users/owner-a/home/shelf', 'type' => 'home-refresh', 'message' => 'App\\Some\\Command'],
            $logger->records[0]['context'],
        );
    }

    public function testPublishAffectedRefreshesAWatchingCardOncePerOwner(): void
    {
        $publisher = new HomeCardPublisher($this->getMercureHub(), [$this->watcher('shelf', [\ArrayObject::class])]);

        $publisher->publishAffected([
            new Change(\ArrayObject::class, '1', ChangeKind::Inserted, 'owner-a'),
            new Change(\ArrayObject::class, '2', ChangeKind::Updated, 'owner-a'),
            new Change(\ArrayObject::class, '3', ChangeKind::Deleted, 'owner-b'),
        ]);

        self::assertCount(2, $this->homeRefreshes());
        $this->assertMercurePublishedOnce('/users/owner-a/home/shelf');
        $this->assertMercurePublishedOnce('/users/owner-b/home/shelf');
    }

    public function testPublishAffectedIgnoresOtherClassesCardsThatDoNotWatchAndRowsWithoutOwner(): void
    {
        $publisher = new HomeCardPublisher($this->getMercureHub(), [
            $this->watcher('shelf', [\ArrayObject::class]),
            $this->plain('tasks'),
        ]);

        $publisher->publishAffected([
            new Change(\SplStack::class, '1', ChangeKind::Updated, 'owner-a'),
            new Change(\ArrayObject::class, '2', ChangeKind::Updated, null),
        ]);

        self::assertSame([], $this->homeRefreshes());
    }

    public function testPublishAffectedMatchesSubclassesOfAWatchedEntity(): void
    {
        $child = new class extends \ArrayObject {};
        $publisher = new HomeCardPublisher($this->getMercureHub(), [$this->watcher('shelf', [\ArrayObject::class])]);

        $publisher->publishAffected([new Change($child::class, '1', ChangeKind::Updated, 'owner-a')]);

        $this->assertMercurePublishedOnce('/users/owner-a/home/shelf');
    }

    /** @param list<class-string> $watched */
    private function watcher(string $module, array $watched): HomeCardProviderInterface
    {
        return new class($module, $watched) implements HomeCardProviderInterface, HomeCardWatcherInterface {
            /** @param list<class-string> $watched */
            public function __construct(private readonly string $module, private readonly array $watched)
            {
            }

            public function module(): string
            {
                return $this->module;
            }

            public function state(User $user, HomeDay $day): array
            {
                return [];
            }

            public function watchedEntities(): array
            {
                return $this->watched;
            }
        };
    }

    private function plain(string $module): HomeCardProviderInterface
    {
        return new class($module) implements HomeCardProviderInterface {
            public function __construct(private readonly string $module)
            {
            }

            public function module(): string
            {
                return $this->module;
            }

            public function state(User $user, HomeDay $day): array
            {
                return [];
            }
        };
    }
}
