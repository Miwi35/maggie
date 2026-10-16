<?php

declare(strict_types=1);

namespace Maggie\Finance\State;

use ApiPlatform\Metadata\Operation;
use ApiPlatform\State\ProcessorInterface;
use Maggie\Finance\Entity\ContactCounterparty;
use Maggie\Finance\Message\UnlinkContactCounterpartyCommand;
use Symfony\Component\Messenger\MessageBusInterface;

/** @implements ProcessorInterface<ContactCounterparty, void> */
class DeleteContactCounterpartyProcessor implements ProcessorInterface
{
    public function __construct(
        private readonly MessageBusInterface $bus,
    ) {
    }

    public function process(mixed $data, Operation $operation, array $uriVariables = [], array $context = []): void
    {
        $this->bus->dispatch(new UnlinkContactCounterpartyCommand(
            userId: (string) $data->getUser()->getId(),
            counterpartyId: (string) $data->getId(),
        ));
    }
}
