<?php

declare(strict_types=1);

namespace Maggie\Finance\State;

use ApiPlatform\Metadata\Operation;
use ApiPlatform\State\ProcessorInterface;
use Maggie\Finance\Entity\Contact;
use Maggie\Finance\Message\DeleteContactCommand;
use Symfony\Component\Messenger\MessageBusInterface;

/** @implements ProcessorInterface<Contact, void> */
class DeleteContactProcessor implements ProcessorInterface
{
    public function __construct(
        private readonly MessageBusInterface $bus,
    ) {
    }

    public function process(mixed $data, Operation $operation, array $uriVariables = [], array $context = []): void
    {
        $this->bus->dispatch(new DeleteContactCommand(
            userId: (string) $data->getUser()->getId(),
            contactId: (string) $data->getId(),
        ));
    }
}
