<?php

namespace Maggie\Agenda\UseCase;

use Maggie\Agenda\Entity\Calendar;
use Doctrine\ORM\EntityManagerInterface;

class CreateCalendar
{
    public function __construct(
        private readonly EntityManagerInterface $em,
    ) {
    }

    public function execute(Calendar $calendar): Calendar
    {
        $this->em->persist($calendar);
        $this->em->flush();

        return $calendar;
    }
}
