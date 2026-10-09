<?php

namespace Maggie\Core\Tests\Projection;

use App\Tests\Support\ElasticsearchAssertionTrait;
use App\Tests\Support\FixtureLoaderTrait;
use App\Tests\Support\MercureAssertionTrait;
use Doctrine\ORM\EntityManagerInterface;
use Lcobucci\JWT\Signer\InvalidKeyProvided;
use Maggie\Core\Elasticsearch\Message\IndexDocumentCommand;
use Maggie\Core\Projection\ProjectionMiddleware;
use Maggie\Core\Projection\WorkCollector;
use Maggie\Grocery\Entity\Store;
use Maggie\Grocery\Message\CheckGroceryItemCommand;
use Maggie\Grocery\Message\DeleteStoreCommand;
use Maggie\Grocery\Message\UpdateStoreCommand;
use Psr\Log\AbstractLogger;
use Psr\Log\LogLevel;
use Symfony\Bundle\FrameworkBundle\Test\KernelTestCase;
use Symfony\Component\Mercure\HubInterface;
use Symfony\Component\Messenger\Envelope;
use Symfony\Component\Messenger\MessageBusInterface;
use Symfony\Component\Messenger\Middleware\MiddlewareInterface;
use Symfony\Component\Messenger\Middleware\StackInterface;
use Symfony\Component\Messenger\Stamp\HandledStamp;
use Symfony\Component\Messenger\Stamp\ReceivedStamp;

/**
 * The unit of work of a root message is projected once, after its handler,
 * to the owner of what changed (MAG-371).
 */
class ProjectionMiddlewareTest extends KernelTestCase
{
    use FixtureLoaderTrait;
    use MercureAssertionTrait;
    use ElasticsearchAssertionTrait;

    private ProjectionMiddleware $middleware;

    protected function setUp(): void
    {
        self::bootKernel();
        $this->loadFixtures(__DIR__.'/../../../grocery/tests/Mcp/fixtures/grocery.yaml');
        $this->em()->clear();
        $this->resetMercure();
        $this->resetAsyncTransport();
        $this->middleware = self::getContainer()->get(ProjectionMiddleware::class);
    }

    private function em(): EntityManagerInterface
    {
        $em = self::getContainer()->get('doctrine.orm.entity_manager');
        \assert($em instanceof EntityManagerInterface);

        return $em;
    }

    private function storeId(): string
    {
        return (string) $this->getFixture('supermarket')->getId();
    }

    private function ownerId(): string
    {
        return (string) $this->getFixture('test_user')->getId();
    }

    /** @param callable(Envelope): void $work what the "handler" does */
    private function stackRunning(callable $work): StackInterface
    {
        $next = new class($work) implements MiddlewareInterface {
            public function __construct(private $work)
            {
            }

            public function handle(Envelope $envelope, StackInterface $stack): Envelope
            {
                ($this->work)($envelope);

                return $envelope->with(new HandledStamp(null, 'handler'));
            }
        };
        $stack = $this->createStub(StackInterface::class);
        $stack->method('next')->willReturn($next);

        return $stack;
    }

    private function received(object $message): Envelope
    {
        return new Envelope($message, [new ReceivedStamp('async')]);
    }

    private function rename(string $name): void
    {
        $store = $this->em()->find(Store::class, $this->storeId());
        $store->setName($name);
        $this->em()->flush();
    }

    public function testAMessageHandledWithNobodyLoggedInIsPublishedToTheOwnerOfTheEntity(): void
    {
        $this->middleware->handle(
            $this->received(new UpdateStoreCommand(storeId: $this->storeId())),
            $this->stackRunning(fn () => $this->rename('Epicerie')),
        );

        $update = $this->getMercureHub()->getUpdates()[0] ?? null;
        self::assertNotNull($update);
        self::assertSame(['/users/'.$this->ownerId().'/api/stores/'.$this->storeId()], $update->getTopics());
        self::assertSame(['@id' => '/api/stores/'.$this->storeId(), 'name' => 'Epicerie'], json_decode($update->getData(), true));
        $this->assertElasticsearchIndexDispatchedFor(Store::class, $this->storeId());
    }

    public function testNothingIsPublishedBeforeTheHandlerHasReturned(): void
    {
        $this->middleware->handle(
            $this->received(new UpdateStoreCommand(storeId: $this->storeId())),
            $this->stackRunning(function () {
                $this->rename('Epicerie');
                $this->assertMercureUpdateCount(0);
                $this->assertNoElasticsearchIndexDispatched(Store::class);
            }),
        );

        $this->assertMercurePublishedOnce('/api/stores/'.$this->storeId());
    }

    public function testWhatAHandlerCommittedBeforeItFailedIsStillProjected(): void
    {
        try {
            $this->middleware->handle(
                $this->received(new UpdateStoreCommand(storeId: $this->storeId())),
                $this->stackRunning(function () {
                    $this->rename('Epicerie');

                    throw new \DomainException('Too late: the row is committed.');
                }),
            );
            self::fail('The handler failure must reach the caller.');
        } catch (\DomainException) {
        }

        $this->assertMercurePublishedOnce('/api/stores/'.$this->storeId());
        $this->assertElasticsearchIndexDispatchedFor(Store::class, $this->storeId());
    }

    public function testAFlushThatFailsProjectsNothing(): void
    {
        try {
            $this->middleware->handle(
                $this->received(new UpdateStoreCommand(storeId: $this->storeId())),
                $this->stackRunning(function () {
                    $store = $this->em()->find(Store::class, $this->storeId());
                    $store->setName(str_repeat('x', 300));
                    $this->em()->flush();
                }),
            );
            self::fail('The flush must fail: the name does not fit the column.');
        } catch (\Throwable) {
        }

        $this->assertMercureUpdateCount(0);
        $this->assertNoElasticsearchIndexDispatched(Store::class);
    }

    public function testANestedMessageIsProjectedWithItsRootAndNeverTwice(): void
    {
        $this->middleware->handle(
            $this->received(new UpdateStoreCommand(storeId: $this->storeId())),
            $this->stackRunning(function () {
                $this->rename('Epicerie');
                $this->middleware->handle(
                    $this->received(new UpdateStoreCommand(storeId: $this->storeId())),
                    $this->stackRunning(function () {
                        $this->rename('Epicerie fine');
                        $this->assertMercureUpdateCount(0);
                    }),
                );
                $this->assertMercureUpdateCount(0);
            }),
        );

        $this->assertMercurePublishedOnce('/api/stores/'.$this->storeId());
        self::assertSame([$this->storeId()], $this->reindexedIdsOf(Store::class));
        self::assertSame('Epicerie fine', $this->mercurePayloadsOn('/api/stores/'.$this->storeId())[0]['name']);
    }

    public function testADeletionIsPublishedToTheOwnerAndTheDocumentRemoved(): void
    {
        $this->middleware->handle(
            $this->received(new DeleteStoreCommand(storeId: $this->storeId())),
            $this->stackRunning(function () {
                $this->em()->remove($this->em()->find(Store::class, $this->storeId()));
                $this->em()->flush();
            }),
        );

        $this->assertMercureDeletePublished('/api/stores/'.$this->storeId());
        $this->assertMercureUpdatePublished('/users/'.$this->ownerId().'/api/stores/');
        $this->assertElasticsearchDeleteDispatched('stores');
    }

    public function testTheFirstPassOfASyncMessageProjectsNothing(): void
    {
        $this->middleware->handle(
            new Envelope(new UpdateStoreCommand(storeId: $this->storeId())),
            $this->stackRunning(fn () => $this->rename('Epicerie')),
        );

        $this->assertMercureUpdateCount(0);
    }

    public function testIndexingMessagesProjectNothing(): void
    {
        $this->middleware->handle(
            $this->received(new IndexDocumentCommand(entityClass: Store::class, entityId: $this->storeId())),
            $this->stackRunning(fn () => $this->rename('Epicerie')),
        );

        $this->assertMercureUpdateCount(0);
    }

    public function testEveryPublishedUpdateIsPrivate(): void
    {
        $this->middleware->handle(
            $this->received(new UpdateStoreCommand(storeId: $this->storeId())),
            $this->stackRunning(fn () => $this->rename('Epicerie')),
        );

        $updates = $this->getMercureHub()->getUpdates();
        self::assertNotSame([], $updates);
        foreach ($updates as $update) {
            self::assertTrue($update->isPrivate(), 'Update on '.implode(', ', $update->getTopics()).' is public.');
        }
    }

    public function testAnActionCommandPublishesItsActionPayloadOnTheListAlone(): void
    {
        $listId = (string) $this->getFixture('grocery_list')->getId();

        self::getContainer()->get(MessageBusInterface::class)->dispatch(new CheckGroceryItemCommand(
            groceryItemId: (string) $this->getFixture('item_tomato')->getId(),
            userId: $this->ownerId(),
            checked: true,
        ));

        $this->assertMercurePublishedOnce('/api/grocery_lists/'.$listId);
        self::assertSame(
            ['@id' => '/api/grocery_lists/'.$listId, 'action' => 'check', 'itemId' => (string) $this->getFixture('item_tomato')->getId(), 'checked' => true],
            $this->mercurePayloadsOn('/api/grocery_lists/'.$listId)[0],
        );
    }

    /** The write is done and a worker would retry the command if we threw: a bad secret must not read like a hub that is down (MAG-141). */
    public function testAnUnsignableSecretIsLoggedAsCriticalAndNamesTheConfiguration(): void
    {
        $logger = $this->recordingLogger();

        $envelope = $this->failingMiddleware(InvalidKeyProvided::tooShort(256, 144), $logger)->handle(
            $this->received(new UpdateStoreCommand(storeId: $this->storeId())),
            $this->stackRunning(fn () => $this->rename('Epicerie')),
        );

        self::assertNotNull($envelope->last(HandledStamp::class), 'The write must still succeed.');
        self::assertSame(LogLevel::CRITICAL, $logger->records[0]['level']);
        self::assertStringContainsString('MERCURE_JWT_SECRET', $logger->records[0]['message']);
    }

    public function testAnUnavailableHubStaysAnOrdinaryLoggedError(): void
    {
        $logger = $this->recordingLogger();

        $this->failingMiddleware(new \RuntimeException('Connection refused'), $logger)->handle(
            $this->received(new UpdateStoreCommand(storeId: $this->storeId())),
            $this->stackRunning(fn () => $this->rename('Epicerie')),
        );

        self::assertSame(LogLevel::ERROR, $logger->records[0]['level']);
        $this->assertElasticsearchIndexDispatchedFor(Store::class, $this->storeId());
    }

    private function failingMiddleware(\Throwable $failure, AbstractLogger $logger): ProjectionMiddleware
    {
        $hub = $this->createStub(HubInterface::class);
        $hub->method('publish')->willThrowException($failure);

        return new ProjectionMiddleware(
            $hub,
            self::getContainer()->get(MessageBusInterface::class),
            self::getContainer()->get(WorkCollector::class),
            $logger,
        );
    }

    private function recordingLogger(): AbstractLogger
    {
        return new class extends AbstractLogger {
            /** @var list<array{level: mixed, message: string}> */
            public array $records = [];

            public function log($level, string|\Stringable $message, array $context = []): void
            {
                $this->records[] = ['level' => $level, 'message' => (string) $message];
            }
        };
    }
}
