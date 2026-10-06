<?php

declare(strict_types=1);

namespace Maggie\Finance\State;

use ApiPlatform\Metadata\Operation;
use ApiPlatform\State\ProcessorInterface;
use Maggie\Finance\Entity\Transaction;
use Maggie\Finance\Message\UpdateTransactionCommand;
use Symfony\Component\Messenger\MessageBusInterface;
use Symfony\Component\Messenger\Stamp\HandledStamp;

/** @implements ProcessorInterface<Transaction, Transaction> */
class UpdateTransactionProcessor implements ProcessorInterface
{
    public function __construct(
        private readonly MessageBusInterface $bus,
    ) {
    }

    public function process(mixed $data, Operation $operation, array $uriVariables = [], array $context = []): Transaction
    {
        $envelope = $this->bus->dispatch(new UpdateTransactionCommand(
            userId: (string) $data->getUser()->getId(),
            transactionId: (string) $data->getId(),
            accountId: (string) $data->getAccount()->getId(),
            amountCents: $data->getAmountCents(),
            label: $data->getLabel(),
            bookedAt: $data->getBookedAt()->format(\DateTimeInterface::ATOM),
            status: $data->getStatus()->value,
            currency: $data->getCurrency(),
            isExceptional: $data->isExceptional(),
            categoryId: null !== $data->getCategory() ? (string) $data->getCategory()->getId() : null,
            retrospect: $data->getRetrospect()->value,
            // A null category after the merge-patch is an explicit clear
            clearFields: null === $data->getCategory() ? ['categoryId'] : [],
        ));

        return $envelope->last(HandledStamp::class)->getResult();
    }
}
