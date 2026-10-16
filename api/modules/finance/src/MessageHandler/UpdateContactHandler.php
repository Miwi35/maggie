<?php

declare(strict_types=1);

namespace Maggie\Finance\MessageHandler;

use Maggie\Finance\Entity\Contact;
use Maggie\Finance\Message\UpdateContactCommand;
use Maggie\Finance\Repository\ContactRepository;
use Maggie\Finance\UseCase\UpdateContact;
use Symfony\Component\Messenger\Attribute\AsMessageHandler;

#[AsMessageHandler]
class UpdateContactHandler
{
    public function __construct(
        private readonly UpdateContact $updateContact,
        private readonly ContactRepository $contactRepository,
    ) {
    }

    public function __invoke(UpdateContactCommand $command): Contact
    {
        $contact = $this->contactRepository->findOneBy(['id' => $command->contactId, 'user' => $command->userId])
            ?? throw new \DomainException("Contact not found: {$command->contactId}");

        if (null !== $command->name) {
            $contact->setName($command->name);
        }
        if (null !== $command->notes) {
            $contact->setNotes($command->notes);
        } elseif ($command->clears('notes')) {
            $contact->setNotes(null);
        }
        CreateContactHandler::assertValid($contact);

        return $this->updateContact->execute($contact);
    }
}
