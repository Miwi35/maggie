<?php

namespace Maggie\Calendar\Tests\Doctrine;

use App\Tests\Support\FixtureLoaderTrait;
use Doctrine\ORM\EntityManagerInterface;
use Doctrine\ORM\Event\OnFlushEventArgs;
use Doctrine\ORM\Event\PreFlushEventArgs;
use Maggie\Calendar\Doctrine\ProactionDeliveryListener;
use Maggie\Calendar\Entity\Proaction;
use Maggie\Calendar\Entity\ProactionOutcome;
use Maggie\Calendar\Enum\ProactionDelivery;
use Maggie\Calendar\Event\ProactionInterrupted;
use Psr\Log\NullLogger;
use Symfony\Bundle\FrameworkBundle\Test\KernelTestCase;
use Symfony\Component\Messenger\Envelope;
use Symfony\Component\Messenger\MessageBusInterface;

class ProactionDeliveryListenerTest extends KernelTestCase
{
    use FixtureLoaderTrait;

    /** @var list<object> */
    private array $dispatched = [];
    /** @var list<bool> */
    private array $transactionOpenWhenSent = [];

    protected function setUp(): void
    {
        self::bootKernel();
        // The listener Doctrine calls records what it would send: the real chain
        // behind it flushes again, which would hide what this one noted.
        self::getContainer()->set(ProactionDeliveryListener::class, new ProactionDeliveryListener($this->spyBus(), new NullLogger()));
        $this->loadFixtures(__DIR__.'/../Mcp/fixtures/ProactionDueTest.yaml');
        $this->dispatched = [];
        $this->transactionOpenWhenSent = [];
    }

    private function em(): EntityManagerInterface
    {
        return self::getContainer()->get('doctrine.orm.entity_manager');
    }

    private function running(): Proaction
    {
        return $this->em()->find(Proaction::class, $this->getFixture('already_running')->getId());
    }

    private function spyBus(): MessageBusInterface
    {
        return new class($this) implements MessageBusInterface {
            public function __construct(private readonly ProactionDeliveryListenerTest $test)
            {
            }

            public function dispatch(object $message, array $stamps = []): Envelope
            {
                $this->test->record($message);

                return new Envelope($message);
            }
        };
    }

    /** @internal */
    public function record(object $event): void
    {
        $this->dispatched[] = $event;
        $this->transactionOpenWhenSent[] = $this->em()->getConnection()->isTransactionActive();
    }

    private function complete(ProactionDelivery $delivery): Proaction
    {
        $proaction = $this->running()->markCompleted(
            new ProactionOutcome(response: 'Loisirs à 72 %.', delivery: $delivery, notice: 'Loisirs à 72 %.', messageLink: '/chat?message=01JABC'),
            new \DateTimeImmutable(),
        );
        $this->em()->flush();

        return $proaction;
    }

    public function testARunCompletingWithAnInterruptionSendsTheEventAfterTheFlush(): void
    {
        $proaction = $this->complete(ProactionDelivery::Interrupt);

        self::assertEquals([new ProactionInterrupted((string) $proaction->getId())], $this->dispatched);
        self::assertSame([false], $this->transactionOpenWhenSent, 'the event goes out after the flush is committed');
    }

    public function testAMessageInTheThreadSendsNothing(): void
    {
        $this->complete(ProactionDelivery::Thread);

        self::assertSame([], $this->dispatched);
    }

    public function testSavingTheCompletedRunAgainSendsNothingMore(): void
    {
        $proaction = $this->complete(ProactionDelivery::Interrupt);
        $this->dispatched = [];

        $proaction->setSummary('Renommée');
        $this->em()->flush();

        self::assertSame([], $this->dispatched);
    }

    public function testAFailedFlushSendsNothingLater(): void
    {
        $this->running()->markCompleted(
            new ProactionOutcome(response: 'x', delivery: ProactionDelivery::Interrupt, messageLink: '/chat?message=01JABC'),
            new \DateTimeImmutable(),
        );
        $listener = new ProactionDeliveryListener($this->spyBus(), new NullLogger());
        $listener->onFlush(new OnFlushEventArgs($this->em()));
        // The flush failed before postFlush: the next one starts clean.
        $listener->preFlush(new PreFlushEventArgs($this->em()));
        $listener->postFlush();

        self::assertSame([], $this->dispatched);
    }
}
