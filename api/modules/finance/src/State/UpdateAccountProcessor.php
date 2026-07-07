<?php

declare(strict_types=1);

namespace Maggie\Finance\State;

use ApiPlatform\Metadata\Operation;
use ApiPlatform\State\ProcessorInterface;
use Maggie\Finance\Entity\Account;
use Maggie\Finance\Message\UpdateAccountCommand;
use Symfony\Component\Messenger\MessageBusInterface;
use Symfony\Component\Messenger\Stamp\HandledStamp;

/** @implements ProcessorInterface<Account, Account> */
class UpdateAccountProcessor implements ProcessorInterface
{
    public function __construct(
        private readonly MessageBusInterface $bus,
    ) {
    }

    public function process(mixed $data, Operation $operation, array $uriVariables = [], array $context = []): Account
    {
        $envelope = $this->bus->dispatch(new UpdateAccountCommand(
            accountId: (string) $data->getId(),
            name: $data->getName(),
            type: $data->getType()->value,
            bank: $data->getBank(),
            currency: $data->getCurrency(),
            balanceCents: $data->getBalanceCents(),
            isCushion: $data->isCushion(),
            bridgeAccountId: $data->getBridgeAccountId(),
        ));

        return $envelope->last(HandledStamp::class)->getResult();
    }
}
