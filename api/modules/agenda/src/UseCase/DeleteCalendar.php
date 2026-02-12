<?php

namespace Maggie\Agenda\UseCase;

use Maggie\Agenda\Entity\Calendar;
use Doctrine\ORM\EntityManagerInterface;

class DeleteCalendar
{
    public function __construct(
        private readonly EntityManagerInterface $em,
    ) {
    }

    public function execute(Calendar $calendar): void
    {
        $this->em->remove($calendar);
        $this->em->flush();
    }
}
