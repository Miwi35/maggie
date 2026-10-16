<?php

declare(strict_types=1);

namespace Maggie\Finance\MessageHandler;

use Maggie\Finance\Message\DeleteContactCommand;
use Maggie\Finance\Repository\ContactRepository;
use Maggie\Finance\UseCase\DeleteContact;
use Symfony\Component\Messenger\Attribute\AsMessageHandler;

#[AsMessageHandler]
class DeleteContactHandler
{
    public function __construct(
        private readonly DeleteContact $deleteContact,
        private readonly ContactRepository $contactRepository,
    ) {
    }

    public function __invoke(DeleteContactCommand $command): void
    {
        $contact = $this->contactRepository->findOneBy(['id' => $command->contactId, 'user' => $command->userId])
            ?? throw new \DomainException("Contact not found: {$command->contactId}");

        $this->deleteContact->execute($contact);
    }
}
