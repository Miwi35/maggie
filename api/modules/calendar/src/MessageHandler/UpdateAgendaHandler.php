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
        if (null === $agenda) {
            throw new \DomainException("Agenda not found: {$command->agendaId}");
        }

        if (null !== $command->name) {
            $agenda->setName($command->name);
        }
        if (null !== $command->description) {
            $agenda->setDescription($command->description);
        } elseif ($command->clears('description')) {
            $agenda->setDescription(null);
        }
        if (null !== $command->timeZone) {
            $agenda->setTimeZone($command->timeZone);
        }
        if (null !== $command->color) {
            $agenda->setColor($command->color);
        } elseif ($command->clears('color')) {
            $agenda->setColor(null);
        }
        if (null !== $command->isDefault) {
            $agenda->setIsDefault($command->isDefault);
        }

        return $this->updateAgenda->execute($agenda);
    }
}
