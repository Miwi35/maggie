<?php

namespace Maggie\Calendar\Tests\MessageHandler;

use App\Tests\Support\ElasticsearchAssertionTrait;
use App\Tests\Support\FixtureLoaderTrait;
use App\Tests\Support\MercureAssertionTrait;
use Doctrine\DBAL\Exception\UniqueConstraintViolationException;
use Doctrine\ORM\EntityManagerInterface;
use Maggie\Calendar\Entity\Agenda;
use Maggie\Calendar\Message\CreateAgendaCommand;
use Maggie\Calendar\Message\CreateEventCommand;
use Maggie\Calendar\Message\UpdateAgendaCommand;
use Maggie\Calendar\Repository\AgendaRepository;
use Maggie\Core\Elasticsearch\Message\IndexDocumentCommand;
use Symfony\Bundle\FrameworkBundle\Test\KernelTestCase;
use Symfony\Component\Messenger\Exception\HandlerFailedException;
use Symfony\Component\Messenger\MessageBusInterface;
use Symfony\Component\Messenger\Stamp\HandledStamp;

class AgendaDefaultTest extends KernelTestCase
{
    use FixtureLoaderTrait;
    use MercureAssertionTrait;
    use ElasticsearchAssertionTrait;

    protected function setUp(): void
    {
        self::bootKernel();
        $this->resetMercure();
        $this->resetAsyncTransport();
        $this->loadFixtures('AgendaDefaultTest.yaml');
    }

    private function em(): EntityManagerInterface
    {
        return self::getContainer()->get('doctrine.orm.entity_manager');
    }

    private function repository(): AgendaRepository
    {
        return self::getContainer()->get(AgendaRepository::class);
    }

    private function dispatch(object $command): mixed
    {
        $envelope = self::getContainer()->get(MessageBusInterface::class)->dispatch($command);

        return $envelope->last(HandledStamp::class)?->getResult();
    }

    private function isDefault(string $fixture): bool
    {
        $this->em()->clear();

        return $this->em()->getRepository(Agenda::class)->find($this->getFixture($fixture)->getId())->isDefault();
    }

    /** @return list<string> */
    private function indexedAgendaIds(): array
    {
        $ids = [];
        foreach ($this->getAsyncTransport()->getSent() as $envelope) {
            $message = $envelope->getMessage();
            if ($message instanceof IndexDocumentCommand && Agenda::class === $message->entityClass) {
                $ids[] = $message->entityId;
            }
        }

        return $ids;
    }

    public function testMarkingAnAgendaAsDefaultTakesTheFlagFromTheOthers(): void
    {
        $this->dispatch(new UpdateAgendaCommand(
            agendaId: (string) $this->getFixture('agenda_concerts')->getId(),
            isDefault: true,
        ));

        self::assertTrue($this->isDefault('agenda_concerts'));
        self::assertFalse($this->isDefault('agenda_main'));
        self::assertCount(1, $this->em()->getRepository(Agenda::class)->findBy([
            'user' => $this->getFixture('test_user'),
            'isDefault' => true,
        ]));
    }

    public function testBothAgendasAreAnnouncedAndIndexed(): void
    {
        $concertsId = (string) $this->getFixture('agenda_concerts')->getId();
        $mainId = (string) $this->getFixture('agenda_main')->getId();

        $this->dispatch(new UpdateAgendaCommand(agendaId: $concertsId, isDefault: true));

        $this->assertMercureUpdatePublished('/agendas/'.$mainId);
        $this->assertMercureUpdatePublished('/agendas/'.$concertsId);
        $this->assertElasticsearchIndexDispatched(Agenda::class);
        $indexed = $this->indexedAgendaIds();
        self::assertContains($mainId, $indexed);
        self::assertContains($concertsId, $indexed);
    }

    public function testAnotherUsersDefaultIsLeftAlone(): void
    {
        $this->dispatch(new UpdateAgendaCommand(
            agendaId: (string) $this->getFixture('agenda_concerts')->getId(),
            isDefault: true,
        ));

        self::assertTrue($this->isDefault('agenda_other'));
    }

    public function testMarkingTheDefaultAgainChangesNothing(): void
    {
        $this->dispatch(new UpdateAgendaCommand(
            agendaId: (string) $this->getFixture('agenda_main')->getId(),
            isDefault: true,
        ));

        self::assertTrue($this->isDefault('agenda_main'));
        self::assertFalse($this->isDefault('agenda_concerts'));
    }

    public function testUnmarkingTheDefaultLeavesTheUserWithNone(): void
    {
        $this->dispatch(new UpdateAgendaCommand(
            agendaId: (string) $this->getFixture('agenda_main')->getId(),
            isDefault: false,
        ));

        self::assertFalse($this->isDefault('agenda_main'));
        $this->em()->clear();
        $user = $this->em()->getRepository(\Maggie\Core\Entity\User::class)->find($this->getFixture('test_user')->getId());
        self::assertNull($this->repository()->findDefault($user), 'No alphabetical fallback: the user has agendas but no default');
    }

    public function testCreatingAnAgendaAsDefaultTakesTheFlagFromTheOthers(): void
    {
        $agenda = $this->dispatch(new CreateAgendaCommand(
            userId: (string) $this->getFixture('test_user')->getId(),
            name: 'Sport',
            isDefault: true,
        ));

        self::assertTrue($agenda->isDefault());
        self::assertFalse($this->isDefault('agenda_main'));
        $this->assertMercureUpdatePublished('/agendas/'.$this->getFixture('agenda_main')->getId());
    }

    public function testTheFirstAgendaCreatedBecomesTheDefault(): void
    {
        $userId = (string) $this->getFixture('fresh_user')->getId();

        $first = $this->dispatch(new CreateAgendaCommand(userId: $userId, name: 'Perso'));
        $second = $this->dispatch(new CreateAgendaCommand(userId: $userId, name: 'Travail'));

        self::assertTrue($first->isDefault());
        self::assertFalse($second->isDefault());
    }

    public function testAFirstGoogleAgendaIsLeftToTheGooglePrimaryRule(): void
    {
        $agenda = $this->dispatch(new CreateAgendaCommand(
            userId: (string) $this->getFixture('fresh_user')->getId(),
            name: 'Concerts',
            googleCalendarId: 'concerts@group.calendar.google.com',
        ));

        self::assertFalse($agenda->isDefault());
    }

    public function testTheDatabaseRefusesASecondDefaultForTheSameUser(): void
    {
        $this->expectException(UniqueConstraintViolationException::class);

        $this->em()->getConnection()->executeStatement(
            'UPDATE agenda SET is_default = true WHERE id = :id',
            ['id' => $this->getFixture('agenda_concerts')->getId()->toRfc4122()],
        );
    }

    public function testAnEventWithoutAgendaFailsWhenThereIsNoDefault(): void
    {
        $this->dispatch(new UpdateAgendaCommand(
            agendaId: (string) $this->getFixture('agenda_main')->getId(),
            isDefault: false,
        ));

        try {
            $this->dispatch(new CreateEventCommand(
                summary: 'Dentist',
                startAt: new \DateTimeImmutable('2026-10-10 10:00'),
                endAt: new \DateTimeImmutable('2026-10-10 11:00'),
                userId: (string) $this->getFixture('test_user')->getId(),
            ));
            self::fail('The event must not be filed in an agenda picked at random.');
        } catch (HandlerFailedException $e) {
            self::assertStringContainsString('No default agenda', $e->getPrevious()->getMessage());
        }

        self::assertSame(0, (int) $this->em()->getConnection()->fetchOne('SELECT COUNT(*) FROM event'));
    }

    public function testAnEventWithoutAgendaGoesToTheDefault(): void
    {
        $this->dispatch(new UpdateAgendaCommand(
            agendaId: (string) $this->getFixture('agenda_concerts')->getId(),
            isDefault: true,
        ));

        $event = $this->dispatch(new CreateEventCommand(
            summary: 'Dentist',
            startAt: new \DateTimeImmutable('2026-10-10 10:00'),
            endAt: new \DateTimeImmutable('2026-10-10 11:00'),
            userId: (string) $this->getFixture('test_user')->getId(),
        ));

        self::assertSame('Concerts', $event->getAgenda()->getName());
    }
}
