<?php

namespace Maggie\Agenda\UseCase;

use Maggie\Agenda\Entity\Event;
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
