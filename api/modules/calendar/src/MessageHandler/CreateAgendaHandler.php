<?php

namespace Maggie\Calendar\MessageHandler;

use Maggie\Calendar\Entity\Agenda;
use Maggie\Calendar\Message\CreateAgendaCommand;
use Maggie\Calendar\UseCase\CreateAgenda;
use Symfony\Component\Messenger\Attribute\AsMessageHandler;

#[AsMessageHandler]
class CreateAgendaHandler
{
    public function __construct(
        private readonly CreateAgenda $createAgenda,
    ) {
    }

    public function __invoke(CreateAgendaCommand $command): Agenda
    {
        $agenda = new Agenda();
        $agenda->setName($command->name);
        $agenda->setTimeZone($command->timeZone);
        $agenda->setIsDefault($command->isDefault);

        if ($command->description !== null) {
            $agenda->setDescription($command->description);
        }
        if ($command->color !== null) {
            $agenda->setColor($command->color);
        }

        return $this->createAgenda->execute($agenda);
    }
}
