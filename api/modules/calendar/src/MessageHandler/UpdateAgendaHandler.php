<?php

namespace Maggie\Calendar\MessageHandler;

use Maggie\Calendar\Entity\Agenda;
use Maggie\Calendar\Message\UpdateAgendaCommand;
use Maggie\Calendar\Repository\AgendaRepository;
use Maggie\Calendar\UseCase\UpdateAgenda;
use Symfony\Component\Messenger\Attribute\AsMessageHandler;

#[AsMessageHandler]
class UpdateAgendaHandler
{
    public function __construct(
        private readonly UpdateAgenda $updateAgenda,
        private readonly AgendaRepository $agendaRepository,
    ) {
    }

    public function __invoke(UpdateAgendaCommand $command): Agenda
    {
        $agenda = $this->agendaRepository->find($command->agendaId);
        if ($agenda === null) {
            throw new \DomainException("Agenda not found: {$command->agendaId}");
        }

        if ($command->name !== null) {
            $agenda->setName($command->name);
        }
        if ($command->description !== null) {
            $agenda->setDescription($command->description);
        }
        if ($command->timeZone !== null) {
            $agenda->setTimeZone($command->timeZone);
        }
        if ($command->color !== null) {
            $agenda->setColor($command->color);
        }
        if ($command->isDefault !== null) {
            $agenda->setIsDefault($command->isDefault);
        }

        return $this->updateAgenda->execute($agenda);
    }
}
