<?php

declare(strict_types=1);

namespace Maggie\Finance\State;

use ApiPlatform\Metadata\Operation;
use ApiPlatform\State\ProcessorInterface;
use Maggie\Finance\Entity\Account;
use Maggie\Finance\Message\DeleteAccountCommand;
use Symfony\Component\Messenger\MessageBusInterface;

/** @implements ProcessorInterface<Account, void> */
class DeleteAccountProcessor implements ProcessorInterface
{
    public function __construct(
        private readonly MessageBusInterface $bus,
    ) {
    }

    public function process(mixed $data, Operation $operation, array $uriVariables = [], array $context = []): void
    {
        $this->bus->dispatch(new DeleteAccountCommand(
            accountId: (string) $data->getId(),
        ));
    }
}
