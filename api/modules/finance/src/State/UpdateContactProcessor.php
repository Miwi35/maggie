<?php

declare(strict_types=1);

namespace Maggie\Finance\State;

use ApiPlatform\Metadata\Operation;
use ApiPlatform\State\ProcessorInterface;
use Maggie\Finance\Entity\Contact;
use Maggie\Finance\Message\UpdateContactCommand;
use Symfony\Component\Messenger\MessageBusInterface;
use Symfony\Component\Messenger\Stamp\HandledStamp;

/** @implements ProcessorInterface<Contact, Contact> */
class UpdateContactProcessor implements ProcessorInterface
{
    public function __construct(
        private readonly MessageBusInterface $bus,
    ) {
    }

    public function process(mixed $data, Operation $operation, array $uriVariables = [], array $context = []): Contact
    {
        $stamped = $this->bus->dispatch(new UpdateContactCommand(
            userId: (string) $data->getUser()->getId(),
            contactId: (string) $data->getId(),
            name: $data->getName(),
            notes: $data->getNotes(),
            // Null notes after the merge-patch are an explicit clear
            clearFields: null === $data->getNotes() ? ['notes'] : [],
        ));

        return $stamped->last(HandledStamp::class)->getResult();
    }
}
