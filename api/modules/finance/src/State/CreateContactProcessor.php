<?php

declare(strict_types=1);

namespace Maggie\Finance\State;

use ApiPlatform\Metadata\Operation;
use ApiPlatform\State\ProcessorInterface;
use Maggie\Core\Entity\User;
use Maggie\Finance\Entity\Contact;
use Maggie\Finance\Message\CreateContactCommand;
use Symfony\Bundle\SecurityBundle\Security;
use Symfony\Component\Messenger\MessageBusInterface;
use Symfony\Component\Messenger\Stamp\HandledStamp;

/** @implements ProcessorInterface<Contact, Contact> */
class CreateContactProcessor implements ProcessorInterface
{
    public function __construct(
        private readonly MessageBusInterface $bus,
        private readonly Security $security,
    ) {
    }

    public function process(mixed $data, Operation $operation, array $uriVariables = [], array $context = []): Contact
    {
        /** @var User $user */
        $user = $this->security->getUser();

        $stamped = $this->bus->dispatch(new CreateContactCommand(
            userId: (string) $user->getId(),
            name: $data->getName(),
            notes: $data->getNotes(),
        ));

        return $stamped->last(HandledStamp::class)->getResult();
    }
}
