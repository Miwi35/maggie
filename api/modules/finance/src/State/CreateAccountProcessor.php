<?php

declare(strict_types=1);

namespace Maggie\Finance\State;

use ApiPlatform\Metadata\Operation;
use ApiPlatform\State\ProcessorInterface;
use Maggie\Core\Entity\User;
use Maggie\Finance\Entity\Account;
use Maggie\Finance\Message\CreateAccountCommand;
use Symfony\Bundle\SecurityBundle\Security;
use Symfony\Component\Messenger\MessageBusInterface;
use Symfony\Component\Messenger\Stamp\HandledStamp;

/** @implements ProcessorInterface<Account, Account> */
class CreateAccountProcessor implements ProcessorInterface
{
    public function __construct(
        private readonly MessageBusInterface $bus,
        private readonly Security $security,
    ) {
    }

    public function process(mixed $data, Operation $operation, array $uriVariables = [], array $context = []): Account
    {
        /** @var User $user */
        $user = $this->security->getUser();

        $envelope = $this->bus->dispatch(new CreateAccountCommand(
            userId: (string) $user->getId(),
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
