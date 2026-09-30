<?php

namespace Maggie\Calendar\UseCase;

use Doctrine\ORM\EntityManagerInterface;
use Maggie\Calendar\Entity\Event;

class UpdateEvent
{
    public function __construct(
        private readonly EntityManagerInterface $em,
    ) {
    }

    public function execute(Event $event): Event
    {
        $this->em->flush();

        return $event;
    }
}
