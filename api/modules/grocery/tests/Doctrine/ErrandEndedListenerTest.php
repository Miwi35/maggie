<?php

namespace Maggie\Grocery\Tests\Doctrine;

use App\Tests\Support\FixtureLoaderTrait;
use Doctrine\ORM\EntityManagerInterface;
use Doctrine\ORM\Events;
use Maggie\Core\Entity\User;
use Maggie\Grocery\Doctrine\ErrandEndedListener;
use Maggie\Grocery\Entity\Errand;
use Maggie\Grocery\Event\ErrandEnded;
use Psr\Log\NullLogger;
use Symfony\Bundle\FrameworkBundle\Test\KernelTestCase;
use Symfony\Component\Messenger\Envelope;
use Symfony\Component\Messenger\MessageBusInterface;

class ErrandEndedListenerTest extends KernelTestCase
{
    use FixtureLoaderTrait;

    /** @var list<object> */
    private array $dispatched = [];

    /** @var list<bool> */
    private array $transactionOpenWhenSent = [];

    private bool $failing = false;

    protected function setUp(): void
    {
        self::bootKernel();
        $this->loadFixtures(__DIR__.'/../Controller/fixtures/end_errand.yaml');

        // The real listener keeps running; this one only records what it would send.
        $this->em()->getEventManager()->addEventListener(
            [Events::preFlush, Events::onFlush, Events::postFlush],
            new ErrandEndedListener($this->spyBus(), new NullLogger()),
        );
    }

    private function em(): EntityManagerInterface
    {
        return self::getContainer()->get('doctrine.orm.entity_manager');
    }

    private function spyBus(): MessageBusInterface
    {
        return new class($this) implements MessageBusInterface {
            public function __construct(private readonly ErrandEndedListenerTest $test)
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
        $this->transactionOpenWhenSent[] = $this->em()->getConnection()->isTransactionActive();
        if ($this->failing) {
            throw new \RuntimeException('Finance is down');
        }
        $this->dispatched[] = $event;
    }

    private function newErrand(?int $paidAmountCents): Errand
    {
        $errand = (new Errand())
            ->setUser($this->em()->find(User::class, $this->getFixture('test_user')->getId()))
            ->setStore($this->getFixture('store_lidl'))
            ->setEndedAt(new \DateTimeImmutable('2026-11-09 18:00:00+01:00'))
            ->setPaidAmountCents($paidAmountCents);
        $this->em()->persist($errand);

        return $errand;
    }

    public function testAStoredErrandRaisesErrandEndedOnceItIsCommitted(): void
    {
        $errand = $this->newErrand(4280);
        $this->em()->flush();

        self::assertEquals([new ErrandEnded(
            (string) $errand->getId(),
            (string) $this->getFixture('test_user')->getId(),
            (string) $this->getFixture('store_lidl')->getId(),
            new \DateTimeImmutable('2026-11-09 18:00:00+01:00'),
            4280,
        )], $this->dispatched);
        self::assertSame([false], $this->transactionOpenWhenSent, 'the event goes out after the flush is committed');
    }

    public function testAnotherFlushDoesNotRaiseItAgain(): void
    {
        $errand = $this->newErrand(null);
        $this->em()->flush();
        $errand->setPaidAmountCents(100);
        $this->em()->flush();

        self::assertCount(1, $this->dispatched);
    }

    public function testAFailingEventDoesNotUndoTheErrand(): void
    {
        $this->failing = true;
        $errand = $this->newErrand(null);
        $this->em()->flush();
        $id = $errand->getId();
        $this->em()->clear();

        self::assertNotNull($this->em()->find(Errand::class, $id));
    }
}
