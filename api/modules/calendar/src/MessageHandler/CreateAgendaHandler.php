<?php

namespace Maggie\Calendar\MessageHandler;

use Maggie\Calendar\Entity\Agenda;
use Maggie\Calendar\Message\CreateAgendaCommand;
use Maggie\Calendar\Message\UpdateAgendaCommand;
use Maggie\Calendar\Repository\AgendaRepository;
use Maggie\Calendar\UseCase\CreateAgenda;
use Maggie\Core\Repository\UserRepository;
use Symfony\Component\Messenger\Attribute\AsMessageHandler;
use Symfony\Component\Messenger\MessageBusInterface;

#[AsMessageHandler]
class CreateAgendaHandler
{
    public function __construct(
        private readonly CreateAgenda $createAgenda,
        private readonly UserRepository $userRepository,
        private readonly AgendaRepository $agendaRepository,
        private readonly MessageBusInterface $bus,
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

        // A user's first agenda is the default until they pick another. A Google
        // calendar is left out: there the primary calendar decides, and
        // ConnectGoogleCalendar passes the flag itself (MAG-149).
        $isDefault = $command->isDefault
            || (null === $command->googleCalendarId && !$this->agendaRepository->userHasAgenda($user));
        $agenda->setIsDefault($isDefault);

        if (null !== $command->description) {
            $agenda->setDescription($command->description);
        }
        if (null !== $command->color) {
            $agenda->setColor($command->color);
        }
        if (null !== $command->googleCalendarId) {
            $agenda->setGoogleCalendarId($command->googleCalendarId);
        }

        if ($isDefault) {
            // Demoted before the flush: the partial unique index allows one default.
            foreach ($this->agendaRepository->findDefaultsExcept($user, $agenda) as $previous) {
                $this->bus->dispatch(new UpdateAgendaCommand(agendaId: (string) $previous->getId(), isDefault: false));
            }
        }

        return $this->createAgenda->execute($agenda);
    }
}
