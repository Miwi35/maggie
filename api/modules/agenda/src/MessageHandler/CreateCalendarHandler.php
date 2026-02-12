<?php

namespace Maggie\Agenda\MessageHandler;

use Maggie\Agenda\Entity\Calendar;
use Maggie\Agenda\Message\CreateCalendarCommand;
use Maggie\Agenda\UseCase\CreateCalendar;
use Symfony\Component\Messenger\Attribute\AsMessageHandler;

#[AsMessageHandler]
class CreateCalendarHandler
{
    public function __construct(
        private readonly CreateCalendar $createCalendar,
    ) {
    }

    public function __invoke(CreateCalendarCommand $command): Calendar
    {
        $calendar = new Calendar();
        $calendar->setName($command->name);
        $calendar->setTimeZone($command->timeZone);
        $calendar->setIsDefault($command->isDefault);

        if ($command->description !== null) {
            $calendar->setDescription($command->description);
        }
        if ($command->color !== null) {
            $calendar->setColor($command->color);
        }

        return $this->createCalendar->execute($calendar);
    }
}
