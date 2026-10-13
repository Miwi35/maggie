<?php

namespace Maggie\Calendar\UseCase;

use Doctrine\ORM\EntityManagerInterface;
use Maggie\Calendar\Entity\Agenda;
use Maggie\Calendar\Exception\AgendaRenameRefusedException;

class UpdateAgenda
{
    public function __construct(
        private readonly EntityManagerInterface $em,
    ) {
    }

    public function execute(Agenda $agenda): Agenda
    {
        $agenda->clearRenameRefusal();
        $this->em->flush();

        // The flush told Google about a rename of a connected agenda (ConnectedAgendaListener), and
        // Google did not take it: the agenda is back to Google's name, and the door that asked is told.
        $refusal = $agenda->getRenameRefusal();
        if (null !== $refusal) {
            throw new AgendaRenameRefusedException($refusal);
        }

        return $agenda;
    }
}
