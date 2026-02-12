<?php

namespace Maggie\Calendar\UseCase;

use Maggie\Calendar\Entity\Event;
use Doctrine\ORM\EntityManagerInterface;

class DeleteEvent
{
    public function __construct(
        private readonly EntityManagerInterface $em,
    ) {
    }

    public function execute(Event $event): void
    {
        $this->em->remove($event);
        $this->em->flush();
    }
}
