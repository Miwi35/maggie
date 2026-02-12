<?php

namespace Maggie\Agenda\MessageHandler;

use Maggie\Agenda\Message\DeleteCalendarCommand;
use Maggie\Agenda\Repository\CalendarRepository;
use Maggie\Agenda\UseCase\DeleteCalendar;
use Symfony\Component\Messenger\Attribute\AsMessageHandler;

#[AsMessageHandler]
class DeleteCalendarHandler
{
    public function __construct(
        private readonly DeleteCalendar $deleteCalendar,
        private readonly CalendarRepository $calendarRepository,
    ) {
    }

    public function __invoke(DeleteCalendarCommand $command): void
    {
        $calendar = $this->calendarRepository->find($command->calendarId);
        if ($calendar === null) {
            throw new \DomainException("Calendar not found: {$command->calendarId}");
        }

        $this->deleteCalendar->execute($calendar);
    }
}
