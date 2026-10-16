<?php

declare(strict_types=1);

namespace Maggie\Finance\MessageHandler;

use Maggie\Finance\Entity\ContactCounterparty;
use Maggie\Finance\Message\LinkContactCounterpartyCommand;
use Maggie\Finance\Repository\ContactRepository;
use Maggie\Finance\UseCase\LinkContactCounterparty;
use Symfony\Component\Messenger\Attribute\AsMessageHandler;

#[AsMessageHandler]
class LinkContactCounterpartyHandler
{
    public function __construct(
        private readonly LinkContactCounterparty $linkContactCounterparty,
        private readonly ContactRepository $contactRepository,
    ) {
    }

    public function __invoke(LinkContactCounterpartyCommand $command): ContactCounterparty
    {
        $contact = $this->contactRepository->findOneBy(['id' => $command->contactId, 'user' => $command->userId])
            ?? throw new \DomainException("Contact not found: {$command->contactId}");

        return $this->linkContactCounterparty->execute($contact, $command->label);
    }
}
