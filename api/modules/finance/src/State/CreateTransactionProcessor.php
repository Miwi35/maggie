<?php

declare(strict_types=1);

namespace Maggie\Finance\State;

use ApiPlatform\Metadata\Operation;
use ApiPlatform\State\ProcessorInterface;
use Maggie\Core\Entity\User;
use Maggie\Finance\Entity\Transaction;
use Maggie\Finance\Message\CreateTransactionCommand;
use Symfony\Bundle\SecurityBundle\Security;
use Symfony\Component\Messenger\MessageBusInterface;
use Symfony\Component\Messenger\Stamp\HandledStamp;

/** @implements ProcessorInterface<Transaction, Transaction> */
class CreateTransactionProcessor implements ProcessorInterface
{
    public function __construct(
        private readonly MessageBusInterface $bus,
        private readonly Security $security,
    ) {
    }

    public function process(mixed $data, Operation $operation, array $uriVariables = [], array $context = []): Transaction
    {
        /** @var User $user */
        $user = $this->security->getUser();

        $envelope = $this->bus->dispatch(new CreateTransactionCommand(
            userId: (string) $user->getId(),
            accountId: (string) $data->getAccount()->getId(),
            amountCents: $data->getAmountCents(),
            label: $data->getLabel(),
            bookedAt: $data->getBookedAt()->format(\DateTimeInterface::ATOM),
            status: $data->getStatus()->value,
            currency: $data->getCurrency(),
            isExceptional: $data->isExceptional(),
            categoryId: $data->getCategory() !== null ? (string) $data->getCategory()->getId() : null,
        ));

        return $envelope->last(HandledStamp::class)->getResult();
    }
}
