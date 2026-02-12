<?php

namespace Maggie\Agenda\UseCase;

use Maggie\Agenda\Entity\Calendar;
use Doctrine\ORM\EntityManagerInterface;

class UpdateCalendar
{
    public function __construct(
        private readonly EntityManagerInterface $em,
    ) {
    }

    public function execute(Calendar $calendar): Calendar
    {
        $this->em->flush();

        return $calendar;
    }
}
