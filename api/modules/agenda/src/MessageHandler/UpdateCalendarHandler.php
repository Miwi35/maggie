<?php

namespace Maggie\Agenda\MessageHandler;

use Maggie\Agenda\Entity\Calendar;
use Maggie\Agenda\Message\UpdateCalendarCommand;
use Maggie\Agenda\Repository\CalendarRepository;
use Maggie\Agenda\UseCase\UpdateCalendar;
use Symfony\Component\Messenger\Attribute\AsMessageHandler;

#[AsMessageHandler]
class UpdateCalendarHandler
{
    public function __construct(
        private readonly UpdateCalendar $updateCalendar,
        private readonly CalendarRepository $calendarRepository,
    ) {
    }

    public function __invoke(UpdateCalendarCommand $command): Calendar
    {
        $calendar = $this->calendarRepository->find($command->calendarId);
        if ($calendar === null) {
            throw new \DomainException("Calendar not found: {$command->calendarId}");
        }

        if ($command->name !== null) {
            $calendar->setName($command->name);
        }
        if ($command->description !== null) {
            $calendar->setDescription($command->description);
        }
        if ($command->timeZone !== null) {
            $calendar->setTimeZone($command->timeZone);
        }
        if ($command->color !== null) {
            $calendar->setColor($command->color);
        }
        if ($command->isDefault !== null) {
            $calendar->setIsDefault($command->isDefault);
        }

        return $this->updateCalendar->execute($calendar);
    }
}
