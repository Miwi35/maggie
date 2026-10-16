<?php

declare(strict_types=1);

namespace Maggie\Finance\MessageHandler;

use Maggie\Core\Repository\UserRepository;
use Maggie\Finance\Entity\Contact;
use Maggie\Finance\Message\CreateContactCommand;
use Maggie\Finance\UseCase\CreateContact;
use Symfony\Component\Messenger\Attribute\AsMessageHandler;

#[AsMessageHandler]
class CreateContactHandler
{
    public function __construct(
        private readonly CreateContact $createContact,
        private readonly UserRepository $userRepository,
    ) {
    }

    public function __invoke(CreateContactCommand $command): Contact
    {
        $user = $this->userRepository->find($command->userId)
            ?? throw new \DomainException('User not found.');

        $contact = (new Contact())
            ->setUser($user)
            ->setName($command->name)
            ->setNotes($command->notes);
        self::assertValid($contact);

        return $this->createContact->execute($contact);
    }

    /** @throws \DomainException */
    public static function assertValid(Contact $contact): void
    {
        if ('' === $contact->getName()) {
            throw new \DomainException('A contact needs a name.');
        }
        if (mb_strlen($contact->getName()) > Contact::NAME_MAX_LENGTH) {
            throw new \DomainException(\sprintf('The name must not exceed %d characters.', Contact::NAME_MAX_LENGTH));
        }
        if (mb_strlen($contact->getNotes() ?? '') > Contact::NOTES_MAX_LENGTH) {
            throw new \DomainException(\sprintf('The notes must not exceed %d characters.', Contact::NOTES_MAX_LENGTH));
        }
    }
}
