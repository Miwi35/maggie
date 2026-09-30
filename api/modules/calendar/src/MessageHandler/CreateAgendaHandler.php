<?php

namespace Maggie\Calendar\MessageHandler;

use Maggie\Calendar\Entity\Agenda;
use Maggie\Calendar\Message\CreateAgendaCommand;
use Maggie\Calendar\UseCase\CreateAgenda;
use Maggie\Core\Repository\UserRepository;
use Symfony\Component\Messenger\Attribute\AsMessageHandler;

#[AsMessageHandler]
class CreateAgendaHandler
{
    public function __construct(
        private readonly CreateAgenda $createAgenda,
        private readonly UserRepository $userRepository,
    ) {
    }

    public function __invoke(CreateAgendaCommand $command): Agenda
    {
        $user = $this->userRepository->find($command->userId)
            ?? throw new \DomainException('User not found.');

        $agenda = new Agenda();
        $agenda->setUser($user);
        $agenda->setName($command->name);
        $agenda->setTimeZone($command->timeZone);
        $agenda->setIsDefault($command->isDefault);

        if (null !== $command->description) {
            $agenda->setDescription($command->description);
        }
        if (null !== $command->color) {
            $agenda->setColor($command->color);
        }

        return $this->createAgenda->execute($agenda);
    }
}
