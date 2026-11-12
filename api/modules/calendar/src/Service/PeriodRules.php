<?php

declare(strict_types=1);

namespace Maggie\Calendar\Service;

use Maggie\Calendar\Entity\Agenda;
use Maggie\Calendar\Repository\AgendaRepository;
use Symfony\Component\Uid\Ulid;

/** What the period handlers refuse before anything is written (MAG-809). */
class PeriodRules
{
    public function __construct(
        private readonly AgendaRepository $agendaRepository,
    ) {
    }

    /** One of the user's own agendas, never another's: the same answer for both as for an unknown id. */
    public function agendaOf(string $userId, string $agendaId): Agenda
    {
        $agenda = Ulid::isValid($agendaId) ? $this->agendaRepository->find($agendaId) : null;
        if (null === $agenda || (string) $agenda->getUser()->getId() !== $userId) {
            throw new \DomainException("Agenda not found: {$agendaId}. Ids come from manage_agendas with action list.");
        }

        return $agenda;
    }
}
