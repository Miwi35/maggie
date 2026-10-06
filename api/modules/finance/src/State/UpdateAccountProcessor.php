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
        // A nullable field that is null after the merge-patch is an explicit clear
        $clearFields = [];
        foreach ([
            'bank' => $data->getBank(),
            'externalAccountId' => $data->getExternalAccountId(),
        ] as $field => $value) {
            if (null === $value) {
                $clearFields[] = $field;
            }
        }

        $envelope = $this->bus->dispatch(new UpdateAccountCommand(
            userId: (string) $data->getUser()->getId(),
            accountId: (string) $data->getId(),
            name: $data->getName(),
            type: $data->getType()->value,
            bank: $data->getBank(),
            currency: $data->getCurrency(),
            balanceCents: $data->getBalanceCents(),
            isCushion: $data->isCushion(),
            externalAccountId: $data->getExternalAccountId(),
            clearFields: $clearFields,
        ));

        return $envelope->last(HandledStamp::class)->getResult();
    }
}
