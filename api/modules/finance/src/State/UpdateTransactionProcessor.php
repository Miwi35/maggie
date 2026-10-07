<?php

declare(strict_types=1);

namespace Maggie\Finance\State;

use ApiPlatform\Metadata\Operation;
use ApiPlatform\State\ProcessorInterface;
use Maggie\Finance\Entity\Transaction;
use Maggie\Finance\Exception\IncompatibleCategoryException;
use Maggie\Finance\Message\UpdateTransactionCommand;
use Symfony\Component\HttpKernel\Exception\BadRequestHttpException;
use Symfony\Component\Messenger\Envelope;
use Symfony\Component\Messenger\Exception\HandlerFailedException;
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
        $envelope = $this->dispatch(new UpdateTransactionCommand(
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

    private function dispatch(object $command): Envelope
    {
        try {
            return $this->bus->dispatch($command);
        } catch (HandlerFailedException $e) {
            $cause = $e->getPrevious();
            if ($cause instanceof IncompatibleCategoryException) {
                throw new BadRequestHttpException($cause->getMessage(), $e);
            }

            throw $e;
        }
    }
}
