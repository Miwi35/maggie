<?php

namespace Maggie\Calendar\MessageHandler;

use Maggie\Calendar\Entity\Agenda;
use Maggie\Calendar\Message\UpdateAgendaCommand;
use Maggie\Calendar\Repository\AgendaRepository;
use Maggie\Calendar\UseCase\UpdateAgenda;
use Symfony\Component\Messenger\Attribute\AsMessageHandler;
use Symfony\Component\Messenger\MessageBusInterface;

#[AsMessageHandler]
class UpdateAgendaHandler
{
    public function __construct(
        private readonly UpdateAgenda $updateAgenda,
        private readonly AgendaRepository $agendaRepository,
        private readonly MessageBusInterface $bus,
    ) {
    }

    public function __invoke(UpdateAgendaCommand $command): Agenda
    {
        $agenda = $this->agendaRepository->find($command->agendaId);
        if (null === $agenda) {
            throw new \DomainException("Agenda not found: {$command->agendaId}");
        }

        if (true === $command->isDefault && $agenda->isModule()) {
            throw new \DomainException('An agenda a module keeps for itself cannot be the default agenda.');
        }

        if (true === $command->isDefault) {
            // One default per user: the others lose it first, through the bus so
            // their screens and the search index follow (MAG-149).
            foreach ($this->agendaRepository->findDefaultsExcept($agenda->getUser(), $agenda) as $previous) {
                $this->bus->dispatch(new UpdateAgendaCommand(agendaId: (string) $previous->getId(), isDefault: false));
            }
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
