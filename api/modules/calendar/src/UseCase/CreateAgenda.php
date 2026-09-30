<?php

namespace Maggie\Calendar\UseCase;

use Doctrine\ORM\EntityManagerInterface;
use Maggie\Calendar\Entity\Agenda;

class CreateAgenda
{
    public function __construct(
        private readonly EntityManagerInterface $em,
    ) {
    }

    public function execute(Agenda $agenda): Agenda
    {
        $this->em->persist($agenda);
        $this->em->flush();

        return $agenda;
    }
}
