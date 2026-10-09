<?php

namespace Maggie\Finance\Tests\Doctrine;

use App\Tests\Support\FixtureLoaderTrait;
use Doctrine\ORM\EntityManagerInterface;
use Doctrine\ORM\Events;
use Maggie\Core\Entity\User;
use Maggie\Finance\Doctrine\TransactionListener;
use Maggie\Finance\Entity\Account;
use Maggie\Finance\Entity\Category;
use Maggie\Finance\Entity\Transaction;
use Maggie\Finance\Enum\TransactionStatus;
use Maggie\Finance\Enum\TransferSource;
use Maggie\Finance\Event\TransactionChanged;
use Maggie\Finance\Event\TransactionRecorded;
use Maggie\Finance\Event\TransactionRemoved;
use Psr\Log\NullLogger;
use Symfony\Bundle\FrameworkBundle\Test\KernelTestCase;
use Symfony\Component\Messenger\Envelope;
use Symfony\Component\Messenger\MessageBusInterface;
use Symfony\Component\Uid\Ulid;

class TransactionListenerTest extends KernelTestCase
{
    use FixtureLoaderTrait;

    /** @var list<object> */
    private array $dispatched = [];

    /** @var list<bool> */
    private array $transactionOpenWhenSent = [];

    /** @var list<bool> */
    private array $storedWhenSent = [];

    protected function setUp(): void
    {
        self::bootKernel();
        $this->loadFixtures(__DIR__.'/../Import/fixtures/two_accounts.yaml');
        $this->dispatched = [];
        $this->transactionOpenWhenSent = [];
        $this->storedWhenSent = [];

        // Only what the listener would send is looked at: the effects behind the events have their own tests.
        $events = $this->em()->getEventManager();
        $events->getAllListeners();
        $events->removeEventListener([Events::preFlush, Events::onFlush, Events::postFlush], self::getContainer()->get(TransactionListener::class));
        $events->addEventListener(
            [Events::preFlush, Events::onFlush, Events::postFlush],
            new TransactionListener($this->spyBus(), new NullLogger()),
        );
    }

    private function em(): EntityManagerInterface
    {
        return self::getContainer()->get('doctrine.orm.entity_manager');
    }

    private function spyBus(): MessageBusInterface
    {
        return new class($this) implements MessageBusInterface {
            public function __construct(private readonly TransactionListenerTest $test)
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
        if ($event instanceof TransactionRecorded) {
            $this->storedWhenSent[] = null !== $this->em()->getConnection()->fetchOne(
                'SELECT 1 FROM '.$this->em()->getClassMetadata(Transaction::class)->getTableName().' WHERE id = ?',
                [(new Ulid($event->transactionId))->toRfc4122()],
            );
        }
        $this->dispatched[] = $event;
    }

    private function stored(string $fixture): Transaction
    {
        return $this->em()->find(Transaction::class, $this->getFixture($fixture)->getId());
    }

    private function newTransaction(string $label, int $amountCents = -1000): Transaction
    {
        $transaction = (new Transaction())
            ->setUser($this->em()->find(User::class, $this->getFixture('test_user')->getId()))
            ->setAccount($this->em()->find(Account::class, $this->getFixture('checking')->getId()))
            ->setAmountCents($amountCents)
            ->setCurrency('EUR')
            ->setBookedAt(new \DateTimeImmutable('2026-09-20'))
            ->setLabel($label)
            ->setStatus(TransactionStatus::Spent);
        $this->em()->persist($transaction);

        return $transaction;
    }

    public function testAnInsertSendsTheRecordedEventWithTheIdOnlyOnceTheTransactionIsStored(): void
    {
        $transaction = $this->newTransaction('PAIEMENT CB');
        $this->em()->flush();

        self::assertEquals([new TransactionRecorded((string) $transaction->getId())], $this->dispatched);
        self::assertSame([false], $this->transactionOpenWhenSent, 'the event goes out after the flush is committed');
        self::assertSame([true], $this->storedWhenSent);
    }

    public function testSeveralInsertsOfOneFlushSendOneEventEachAfterAllAreStored(): void
    {
        $out = $this->newTransaction('VIREMENT VERS LIVRET', -50000);
        $in = $this->newTransaction('VIREMENT DU COURANT', 50000);
        $this->em()->flush();

        self::assertEquals([
            new TransactionRecorded((string) $out->getId()),
            new TransactionRecorded((string) $in->getId()),
        ], $this->dispatched);
    }

    public function testChangingTheLabelSendsTheChangedEventNamingTheField(): void
    {
        $transaction = $this->stored('plain_expense');
        $transaction->setLabel('CARREFOUR MARKET 4412');
        $this->em()->flush();

        self::assertEquals([new TransactionChanged((string) $transaction->getId(), ['label'])], $this->dispatched);
    }

    public function testChangingTheAmountTheDateOrTheAccountSendsTheChangedEvent(): void
    {
        $transaction = $this->stored('plain_expense');
        $transaction->setAmountCents(-5000)
            ->setBookedAt(new \DateTimeImmutable('2026-09-02'))
            ->setAccount($this->em()->find(Account::class, $this->getFixture('savings')->getId()));
        $this->em()->flush();

        self::assertCount(1, $this->dispatched, 'one event per transaction, however many fields moved');
        self::assertEqualsCanonicalizing(['amountCents', 'bookedAt', 'account'], $this->dispatched[0]->changedFields);
    }

    public function testAChangeThatNoRuleAndNoDetectionReadsSendsNothing(): void
    {
        $transaction = $this->stored('plain_expense');
        $transaction->assignCategory($this->em()->find(Category::class, $this->getFixture('food')->getId()), \Maggie\Finance\Enum\CategorySource::Manual);
        $transaction->setIsExceptional(true);
        $this->em()->flush();

        self::assertSame([], $this->dispatched);
    }

    public function testSavingTheSameStateAgainSendsNothing(): void
    {
        $transaction = $this->stored('plain_expense');
        $transaction->setLabel($transaction->getLabel());
        $this->em()->flush();
        $this->em()->flush();

        self::assertSame([], $this->dispatched);
    }

    public function testRemovingAPairedLegSendsTheRemovedEventNamingTheOtherLeg(): void
    {
        $out = $this->stored('transfer_out');
        $in = $this->stored('transfer_in');
        $out->markAsInternalTransfer($in, TransferSource::Auto);
        $this->em()->flush();
        $this->dispatched = [];
        $outId = (string) $out->getId();
        $inId = (string) $in->getId();

        $this->em()->remove($out);
        $this->em()->flush();

        self::assertEquals([new TransactionRemoved($outId, $inId)], $this->dispatched);
    }

    public function testRemovingAnUnpairedTransactionSendsNothing(): void
    {
        $this->em()->remove($this->stored('plain_expense'));
        $this->em()->flush();

        self::assertSame([], $this->dispatched);
    }

    public function testChangesOfAFlushThatFailedAreNotSentWithTheNextFlush(): void
    {
        $this->newTransaction('PAIEMENT CB');
        $events = $this->em()->getEventManager();
        $boom = new class {
            public function onFlush(): void
            {
                throw new \RuntimeException('flush failed');
            }
        };
        $events->addEventListener([Events::onFlush], $boom);
        try {
            $this->em()->flush();
            self::fail('The flush should have failed.');
        } catch (\RuntimeException) {
        }
        $events->removeEventListener([Events::onFlush], $boom);
        $this->em()->clear();

        $this->stored('plain_expense')->setIsExceptional(true);
        $this->em()->flush();

        self::assertSame([], $this->dispatched);
    }

    public function testAFailingEventDoesNotUndoTheSave(): void
    {
        $this->em()->getEventManager()->addEventListener(
            [Events::preFlush, Events::onFlush, Events::postFlush],
            new TransactionListener(new class implements MessageBusInterface {
                public function dispatch(object $message, array $stamps = []): Envelope
                {
                    throw new \RuntimeException('effect failed');
                }
            }, new NullLogger()),
        );

        $transaction = $this->newTransaction('PAIEMENT CB');
        $this->em()->flush();

        $this->em()->clear();
        self::assertNotNull($this->em()->find(Transaction::class, $transaction->getId()));
        self::assertCount(1, $this->dispatched, 'the other listener still got its event');
    }
}
