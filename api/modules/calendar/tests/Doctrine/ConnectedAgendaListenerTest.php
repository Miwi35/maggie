<?php

namespace Maggie\Calendar\Tests\Doctrine;

use App\Tests\Support\FixtureLoaderTrait;
use Doctrine\ORM\EntityManagerInterface;
use Doctrine\ORM\Events;
use Maggie\Calendar\Doctrine\ConnectedAgendaListener;
use Maggie\Calendar\Entity\Agenda;
use Maggie\Calendar\Event\ConnectedAgendaRenamed;
use Psr\Log\NullLogger;
use Symfony\Bundle\FrameworkBundle\Test\KernelTestCase;
use Symfony\Component\Messenger\Envelope;
use Symfony\Component\Messenger\MessageBusInterface;

class ConnectedAgendaListenerTest extends KernelTestCase
{
    use FixtureLoaderTrait;

    /** @var list<object> */
    private array $dispatched = [];
    /** @var list<string> the name stored when each event was sent */
    private array $storedNameWhenSent = [];
    /** @var list<bool> */
    private array $transactionOpenWhenSent = [];

    protected function setUp(): void
    {
        self::bootKernel();
        $this->loadFixtures(__DIR__.'/../MessageHandler/fixtures/ConnectedAgendaRenameTest.yaml');
        $this->dispatched = [];
        $this->storedNameWhenSent = [];
        $this->transactionOpenWhenSent = [];

        // The real listener keeps running, and the real chain behind the event with it: this one only
        // records what it would send, and the Google client behind the real chain is never reached here
        // because the stub below answers for it.
        $this->stubGoogleClient();
        $this->em()->getEventManager()->addEventListener(
            [Events::preFlush, Events::onFlush, Events::postFlush],
            new ConnectedAgendaListener($this->spyBus(), new NullLogger()),
        );
    }

    private function stubGoogleClient(): void
    {
        self::getContainer()->set(
            \Maggie\Calendar\Service\GoogleCalendarApiClient::class,
            $this->createStub(\Maggie\Calendar\Service\GoogleCalendarApiClient::class),
        );
    }

    private function em(): EntityManagerInterface
    {
        return self::getContainer()->get('doctrine.orm.entity_manager');
    }

    private function agenda(string $fixture): Agenda
    {
        return $this->em()->find(Agenda::class, $this->getFixture($fixture)->getId());
    }

    private function spyBus(): MessageBusInterface
    {
        return new class($this) implements MessageBusInterface {
            public function __construct(private readonly ConnectedAgendaListenerTest $test)
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
        $this->storedNameWhenSent[] = (string) $this->em()->getConnection()->fetchOne(
            'SELECT name FROM agenda WHERE id = ?',
            [$this->agenda('connected_agenda')->getId()->toRfc4122()],
        );
    }

    public function testRenamingAConnectedAgendaSendsTheEventOnceTheNameIsStored(): void
    {
        $agenda = $this->agenda('connected_agenda');
        $agenda->setName('Ael');
        $this->em()->flush();

        self::assertEquals([new ConnectedAgendaRenamed((string) $agenda->getId(), 'Garde Ael')], $this->dispatched);
        self::assertSame(['Ael'], $this->storedNameWhenSent);
        self::assertSame([false], $this->transactionOpenWhenSent, 'the event goes out after the flush is committed');
    }

    public function testRenamingAnAgendaNotConnectedToGoogleSendsNothing(): void
    {
        $this->agenda('local_agenda')->setName('Mon agenda');
        $this->em()->flush();

        self::assertSame([], $this->dispatched);
    }

    public function testANameAdoptedFromGoogleSendsNothing(): void
    {
        $this->agenda('connected_agenda')->adoptGoogleName('Renommé chez Google');
        $this->em()->flush();

        self::assertSame([], $this->dispatched);
    }

    public function testAnotherChangeSendsNothing(): void
    {
        $this->agenda('connected_agenda')->setColor('#123456');
        $this->em()->flush();

        self::assertSame([], $this->dispatched);
    }

    public function testChangesOfAFlushThatFailedAreNotSentWithTheNextFlush(): void
    {
        $this->agenda('connected_agenda')->setName('Ael');
        $manager = $this->em()->getEventManager();
        $boom = new class {
            public function onFlush(): void
            {
                throw new \RuntimeException('flush failed');
            }
        };
        $manager->addEventListener([Events::onFlush], $boom);
        try {
            $this->em()->flush();
            self::fail('The flush should have failed.');
        } catch (\RuntimeException) {
        }
        $manager->removeEventListener([Events::onFlush], $boom);
        $this->dispatched = [];

        $this->em()->clear();
        $this->agenda('connected_agenda')->setColor('#123456');
        $this->em()->flush();

        self::assertSame([], $this->dispatched);
    }

    public function testAFailingEventDoesNotUndoTheSave(): void
    {
        $this->em()->getEventManager()->addEventListener(
            [Events::postFlush],
            new ConnectedAgendaListener(new class implements MessageBusInterface {
                public function dispatch(object $message, array $stamps = []): Envelope
                {
                    throw new \RuntimeException('push failed');
                }
            }, new NullLogger()),
        );

        $this->agenda('connected_agenda')->setName('Ael');
        $this->em()->flush();

        $this->em()->clear();
        self::assertSame('Ael', $this->agenda('connected_agenda')->getName());
        self::assertCount(1, $this->dispatched, 'the other listener still got its event');
    }
}
