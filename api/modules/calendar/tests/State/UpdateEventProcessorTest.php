<?php

namespace Maggie\Calendar\Tests\State;

use ApiPlatform\Metadata\Patch;
use Maggie\Calendar\Entity\Agenda;
use Maggie\Calendar\Entity\Event;
use Maggie\Calendar\Enum\EventStatus;
use Maggie\Calendar\Message\UpdateEventCommand;
use Maggie\Calendar\State\UpdateEventProcessor;
use PHPUnit\Framework\TestCase;
use Symfony\Component\Messenger\Envelope;
use Symfony\Component\Messenger\MessageBusInterface;
use Symfony\Component\Messenger\Stamp\HandledStamp;

class UpdateEventProcessorTest extends TestCase
{
    private ?UpdateEventCommand $dispatched = null;

    private function process(Event $data, ?Event $previous): UpdateEventCommand
    {
        $bus = $this->createStub(MessageBusInterface::class);
        $bus->method('dispatch')->willReturnCallback(function (UpdateEventCommand $command) use ($data) {
            $this->dispatched = $command;

            return new Envelope($command, [new HandledStamp($data, 'handler')]);
        });

        (new UpdateEventProcessor($bus))->process($data, new Patch(), [], ['previous_data' => $previous]);

        return $this->dispatched;
    }

    private function event(Agenda $agenda): Event
    {
        $event = new Event();
        $event->setSummary('Dentist');
        $event->setStartAt(new \DateTimeImmutable('2026-03-15 10:00'));
        $event->setEndAt(new \DateTimeImmutable('2026-03-15 11:00'));
        $event->setAgenda($agenda);

        return $event;
    }

    public function testStatusChangeIsSent(): void
    {
        $agenda = new Agenda();
        $previous = $this->event($agenda);
        $data = clone $previous;
        $data->setStatus(EventStatus::Cancelled);

        $command = $this->process($data, $previous);

        self::assertSame('cancelled', $command->status);
    }

    public function testAgendaChangeIsSent(): void
    {
        $previous = $this->event(new Agenda());
        $target = new Agenda();
        $data = clone $previous;
        $data->setAgenda($target);

        $command = $this->process($data, $previous);

        self::assertSame((string) $target->getId(), $command->agendaId);
    }

    public function testRemindersChangeIsSent(): void
    {
        $previous = $this->event(new Agenda());
        $data = clone $previous;
        $reminders = ['useDefault' => false, 'overrides' => [['method' => 'popup', 'minutes' => 30]]];
        $data->setReminders($reminders);

        $command = $this->process($data, $previous);

        self::assertSame($reminders, $command->reminders);
    }

    public function testRemindersSetToNullAreAnExplicitClear(): void
    {
        $previous = $this->event(new Agenda());
        $previous->setReminders(['useDefault' => true]);
        $data = clone $previous;
        $data->setReminders(null);

        $command = $this->process($data, $previous);

        self::assertNull($command->reminders);
        self::assertTrue($command->clears('reminders'));
    }

    public function testUnchangedStatusAgendaAndRemindersAreNotSent(): void
    {
        $previous = $this->event(new Agenda());
        $previous->setReminders(['useDefault' => true]);
        $data = clone $previous;
        $data->setSummary('Renamed');

        $command = $this->process($data, $previous);

        self::assertNull($command->status);
        self::assertNull($command->agendaId);
        self::assertNull($command->reminders);
        self::assertFalse($command->clears('reminders'));
    }
}
