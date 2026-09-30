<?php

namespace Maggie\Calendar\Tests\MessageHandler;

use Maggie\Calendar\Entity\Agenda;
use Maggie\Calendar\Entity\Event;
use Maggie\Calendar\Enum\EventStatus;
use Maggie\Calendar\Message\DeleteEventFromGoogleCommand;
use Maggie\Calendar\Message\PushEventToGoogleCommand;
use Maggie\Calendar\Message\UpdateEventCommand;
use Maggie\Calendar\MessageHandler\UpdateEventHandler;
use Maggie\Calendar\Repository\AgendaRepository;
use Maggie\Calendar\Repository\EventRepository;
use Maggie\Calendar\UseCase\UpdateEvent;
use Maggie\Core\Entity\User;
use PHPUnit\Framework\TestCase;
use Psr\Log\NullLogger;
use Symfony\Component\Messenger\Envelope;
use Symfony\Component\Messenger\MessageBusInterface;

class UpdateEventHandlerGoogleTest extends TestCase
{
    /** @var list<object> */
    private array $dispatched = [];
    private User $user;

    protected function setUp(): void
    {
        $this->dispatched = [];
        $this->user = new User();
    }

    private function agenda(?string $googleCalendarId, ?User $user = null): Agenda
    {
        $agenda = (new Agenda())->setUser($user ?? $this->user)->setName('Agenda')->setGoogleCalendarId($googleCalendarId);

        return $agenda;
    }

    private function event(Agenda $agenda, ?string $googleEventId): Event
    {
        $event = new Event();
        $event->setSummary('Dentist');
        $event->setStartAt(new \DateTimeImmutable('2026-03-15 10:00'));
        $event->setEndAt(new \DateTimeImmutable('2026-03-15 11:00'));
        $event->setAgenda($agenda);
        $event->setGoogleEventId($googleEventId);
        $event->setGoogleEtag('"etag"');

        return $event;
    }

    private function handle(Event $event, UpdateEventCommand $command, ?Agenda $target = null): Event
    {
        $events = $this->createStub(EventRepository::class);
        $events->method('find')->willReturn($event);

        $agendas = $this->createStub(AgendaRepository::class);
        $agendas->method('find')->willReturn($target);

        $em = $this->createStub(\Doctrine\ORM\EntityManagerInterface::class);

        $bus = $this->createStub(MessageBusInterface::class);
        $bus->method('dispatch')->willReturnCallback(function (object $message) {
            $this->dispatched[] = $message;

            return new Envelope($message);
        });

        return (new UpdateEventHandler(new UpdateEvent($em), $events, $agendas, $bus, new NullLogger()))($command);
    }

    public function testStatusAndRemindersAreAppliedAndPushedAsChangedFields(): void
    {
        $event = $this->event($this->agenda('cal-a'), 'g-1');

        $this->handle($event, new UpdateEventCommand(
            eventId: (string) $event->getId(),
            summary: 'Renamed',
            status: 'cancelled',
            reminders: ['useDefault' => true],
        ));

        self::assertSame(EventStatus::Cancelled, $event->getStatus());
        self::assertSame(['useDefault' => true], $event->getReminders());
        $push = $this->dispatched[0];
        self::assertInstanceOf(PushEventToGoogleCommand::class, $push);
        self::assertSame('update', $push->action);
        self::assertEqualsCanonicalizing(['summary', 'status', 'reminders'], $push->changedFields);
    }

    public function testClearingRemindersIsApplied(): void
    {
        $event = $this->event($this->agenda(null), null);
        $event->setReminders(['useDefault' => true]);

        $this->handle($event, new UpdateEventCommand(eventId: (string) $event->getId(), clearFields: ['reminders']));

        self::assertNull($event->getReminders());
    }

    public function testMovingBetweenTwoSyncedAgendasAsksGoogleToMoveTheEvent(): void
    {
        $from = $this->agenda('cal-a');
        $to = $this->agenda('cal-b');
        $event = $this->event($from, 'g-1');

        $this->handle($event, new UpdateEventCommand(eventId: (string) $event->getId(), agendaId: (string) $to->getId()), $to);

        self::assertSame($to, $event->getAgenda());
        $push = $this->dispatched[0];
        self::assertInstanceOf(PushEventToGoogleCommand::class, $push);
        self::assertSame('move', $push->action);
        self::assertSame('cal-a', $push->fromGoogleCalendarId);
        self::assertSame(['agenda'], $push->changedFields);
    }

    public function testMovingFromASyncedAgendaToALocalOneRemovesTheGoogleCopy(): void
    {
        $from = $this->agenda('cal-a');
        $to = $this->agenda(null);
        $event = $this->event($from, 'g-1');

        $this->handle($event, new UpdateEventCommand(eventId: (string) $event->getId(), agendaId: (string) $to->getId()), $to);

        self::assertNull($event->getGoogleEventId());
        self::assertNull($event->getGoogleEtag());
        self::assertCount(1, $this->dispatched);
        $delete = $this->dispatched[0];
        self::assertInstanceOf(DeleteEventFromGoogleCommand::class, $delete);
        self::assertSame((string) $from->getId(), $delete->agendaId);
        self::assertSame('g-1', $delete->googleEventId);
    }

    public function testMovingFromALocalAgendaToASyncedOneCreatesTheGoogleEvent(): void
    {
        $from = $this->agenda(null);
        $to = $this->agenda('cal-b');
        $event = $this->event($from, null);

        $this->handle($event, new UpdateEventCommand(eventId: (string) $event->getId(), agendaId: (string) $to->getId()), $to);

        $push = $this->dispatched[0];
        self::assertInstanceOf(PushEventToGoogleCommand::class, $push);
        self::assertSame('create', $push->action);
    }

    public function testMovingBetweenLocalAgendasPushesNothing(): void
    {
        $to = $this->agenda(null);
        $event = $this->event($this->agenda(null), null);

        $this->handle($event, new UpdateEventCommand(eventId: (string) $event->getId(), agendaId: (string) $to->getId()), $to);

        self::assertSame($to, $event->getAgenda());
        self::assertSame([], $this->dispatched);
    }

    public function testMovingToAnotherUsersAgendaIsRefused(): void
    {
        $from = $this->agenda('cal-a');
        $foreign = $this->agenda(null, new User());
        $event = $this->event($from, 'g-1');

        $this->expectException(\DomainException::class);

        try {
            $this->handle($event, new UpdateEventCommand(eventId: (string) $event->getId(), agendaId: (string) $foreign->getId()), $foreign);
        } finally {
            self::assertSame($from, $event->getAgenda());
            self::assertSame([], $this->dispatched);
        }
    }

    public function testMovingToAnUnknownAgendaIsRefused(): void
    {
        $event = $this->event($this->agenda('cal-a'), 'g-1');

        $this->expectException(\DomainException::class);

        $this->handle($event, new UpdateEventCommand(eventId: (string) $event->getId(), agendaId: '01ARZ3NDEKTSV4RRFFQ69G5FAV'), null);
    }
}
