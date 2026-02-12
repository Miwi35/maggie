<?php

namespace Maggie\Calendar\UseCase;

use Maggie\Calendar\Entity\Agenda;
use Doctrine\ORM\EntityManagerInterface;

class DeleteAgenda
{
    public function __construct(
        private readonly EntityManagerInterface $em,
    ) {
    }

    public function execute(Agenda $agenda): void
    {
        $this->em->remove($agenda);
        $this->em->flush();
    }
}
