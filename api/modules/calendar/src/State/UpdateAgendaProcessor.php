<?php

namespace Maggie\Calendar\State;

use ApiPlatform\Metadata\Operation;
use ApiPlatform\State\ProcessorInterface;
use Doctrine\ORM\EntityManagerInterface;
use Maggie\Calendar\Entity\Agenda;
use Maggie\Calendar\Message\UpdateAgendaCommand;
use Symfony\Component\Messenger\MessageBusInterface;
use Symfony\Component\Messenger\Stamp\HandledStamp;

/** @implements ProcessorInterface<Agenda, Agenda> */
class UpdateAgendaProcessor implements ProcessorInterface
{
    public function __construct(
        private readonly MessageBusInterface $bus,
        private readonly EntityManagerInterface $em,
    ) {
    }

    public function process(mixed $data, Operation $operation, array $uriVariables = [], array $context = []): Agenda
    {
        // This processor sends every field: a nullable one that is null is an explicit clear
        $clearFields = [];
        if (null === $data->getDescription()) {
            $clearFields[] = 'description';
        }
        if (null === $data->getColor()) {
            $clearFields[] = 'color';
        }

        $command = new UpdateAgendaCommand(
            agendaId: (string) $data->getId(),
            name: $data->getName(),
            description: $data->getDescription(),
            timeZone: $data->getTimeZone(),
            color: $data->getColor(),
            isDefault: $data->isDefault(),
            clearFields: $clearFields,
        );

        // The deserializer already applied the patch to the managed entity: any flush the handler
        // triggers (demoting the previous default) would write the promotion first and trip the
        // unique index. The command carries every field, so start from the stored state.
        $this->em->refresh($data);

        $envelope = $this->bus->dispatch($command);

        return $envelope->last(HandledStamp::class)->getResult();
    }
}
