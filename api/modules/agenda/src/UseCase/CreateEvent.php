<?php

namespace Maggie\Agenda\UseCase;

use Maggie\Agenda\Entity\Event;
use Doctrine\ORM\EntityManagerInterface;

class CreateEvent
{
    public function __construct(
        private readonly EntityManagerInterface $em,
    ) {
    }

    public function execute(Event $event): Event
    {
        $this->em->persist($event);
        $this->em->flush();

        return $event;
    }
}
