<?php

namespace Maggie\Grocery\Tests\MessageHandler;

use App\Tests\Support\ElasticsearchAssertionTrait;
use App\Tests\Support\FixtureLoaderTrait;
use App\Tests\Support\MercureAssertionTrait;
use Doctrine\ORM\EntityManagerInterface;
use Maggie\Grocery\Entity\Errand;
use Maggie\Grocery\Event\ErrandEnded;
use Maggie\Grocery\Exception\InvalidErrandException;
use Maggie\Grocery\Message\EndErrandCommand;
use Symfony\Bundle\FrameworkBundle\Test\KernelTestCase;
use Symfony\Component\Messenger\Envelope;
use Symfony\Component\Messenger\Exception\HandlerFailedException;
use Symfony\Component\Messenger\MessageBusInterface;

/** The amount paid at a store's till, and the ErrandEnded each errand raises (MAG-765). */
class EndErrandHandlerTest extends KernelTestCase
{
    use FixtureLoaderTrait;
    use MercureAssertionTrait;
    use ElasticsearchAssertionTrait;

    /** @var list<object> what reached the event bus */
    private array $dispatched = [];

    protected function setUp(): void
    {
        self::bootKernel();
        // Before anything flushes: the listener takes its bus when Doctrine first needs it.
        self::getContainer()->set('event.bus', $this->spyBus());
        $this->loadFixtures(__DIR__.'/../Controller/fixtures/end_errand.yaml');
        $this->dispatched = [];
        $this->resetMercure();
        $this->resetAsyncTransport();
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

    private function dispatch(?string $storeId, ?int $paidAmountCents): void
    {
        self::getContainer()->get(MessageBusInterface::class)->dispatch(new EndErrandCommand(
            userId: (string) $this->getFixture('test_user')->getId(),
            storeId: $storeId,
            paidAmountCents: $paidAmountCents,
        ));
    }

    /** @return list<Errand> */
    private function errands(): array
    {
        $em = self::getContainer()->get('doctrine.orm.entity_manager');
        \assert($em instanceof EntityManagerInterface);
        $em->clear();

        return $em->getRepository(Errand::class)->findAll();
    }

    /** @return list<ErrandEnded> */
    private function ended(): array
    {
        return array_values(array_filter($this->dispatched, static fn (object $event) => $event instanceof ErrandEnded));
    }

    public function testThePaidAmountIsStoredOnTheStoresErrandAndErrandEndedIsRaisedOnce(): void
    {
        $lidlId = (string) $this->getFixture('store_lidl')->getId();

        $this->dispatch($lidlId, 4280);

        $errands = $this->errands();
        self::assertCount(1, $errands);
        self::assertSame(4280, $errands[0]->getPaidAmountCents());

        $ended = $this->ended();
        self::assertCount(1, $ended);
        self::assertSame((string) $errands[0]->getId(), $ended[0]->errandId);
        self::assertSame((string) $this->getFixture('test_user')->getId(), $ended[0]->userId);
        self::assertSame($lidlId, $ended[0]->storeId);
        self::assertSame(4280, $ended[0]->paidAmountCents);
        self::assertSame($errands[0]->getEndedAt()->format(\DateTimeInterface::ATOM), $ended[0]->endedAt->format(\DateTimeInterface::ATOM));

        $this->assertMercureUpdatePublished('/api/errands/'.$errands[0]->getId());
        $this->assertElasticsearchIndexDispatchedFor(Errand::class, (string) $errands[0]->getId());
    }

    public function testWithoutAnAmountTheErrandHasNone(): void
    {
        $this->dispatch((string) $this->getFixture('store_lidl')->getId(), null);

        $errands = $this->errands();
        self::assertCount(1, $errands);
        self::assertNull($errands[0]->getPaidAmountCents());
        self::assertCount(1, $this->ended());
        self::assertNull($this->ended()[0]->paidAmountCents);
    }

    public function testEndingWithoutStoreRaisesOneEventPerErrand(): void
    {
        $this->dispatch(null, null);

        self::assertCount(2, $this->errands());
        self::assertCount(2, $this->ended());
    }

    public function testNothingBoughtRaisesNothing(): void
    {
        $this->dispatch('01ARZ3NDEKTSV4RRFFQ69G5FAV', 1000);

        self::assertSame([], $this->errands());
        self::assertSame([], $this->ended());
    }

    public function testANegativeAmountIsRefused(): void
    {
        $this->assertRefused((string) $this->getFixture('store_lidl')->getId(), -1, 'zero or more');
    }

    public function testAnAmountWithoutAStoreIsRefused(): void
    {
        $this->assertRefused(null, 4280, 'storeId');
    }

    private function assertRefused(?string $storeId, int $paidAmountCents, string $message): void
    {
        try {
            $this->dispatch($storeId, $paidAmountCents);
            self::fail('The errand should have been refused.');
        } catch (HandlerFailedException $e) {
            self::assertInstanceOf(InvalidErrandException::class, $e->getPrevious());
            self::assertStringContainsString($message, $e->getPrevious()->getMessage());
        }

        self::assertSame([], $this->errands());
        self::assertSame([], $this->ended());
        $this->assertMercureUpdateCount(0);
    }
}
