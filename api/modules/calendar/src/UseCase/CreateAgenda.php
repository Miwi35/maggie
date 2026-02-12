<?php

namespace Maggie\Calendar\UseCase;

use Maggie\Calendar\Entity\Agenda;
use Doctrine\ORM\EntityManagerInterface;

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
