<?php

namespace Maggie\Calendar\UseCase;

use Doctrine\ORM\EntityManagerInterface;
use Maggie\Calendar\Entity\Event;

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
