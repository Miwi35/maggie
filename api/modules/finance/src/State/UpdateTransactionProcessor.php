<?php

declare(strict_types=1);

namespace Maggie\Finance\State;

use ApiPlatform\Metadata\Operation;
use ApiPlatform\State\ProcessorInterface;
use Maggie\Finance\Entity\Transaction;
use Maggie\Finance\Exception\IncompatibleCategoryException;
use Maggie\Finance\Exception\RecurringAttachmentException;
use Maggie\Finance\Message\UpdateTransactionCommand;
use Symfony\Component\HttpKernel\Exception\BadRequestHttpException;
use Symfony\Component\HttpKernel\Exception\UnprocessableEntityHttpException;
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
        /** @var Transaction|null $previous */
        $previous = $context['previous_data'] ?? null;

        // Only a change of the attachment is a gesture: every PATCH carries the
        // whole merged line, and re-sending an automatic attachment must not
        // seal it `manual`.
        $series = $data->getRecurringOperation();
        $occurrenceOn = $data->getRecurringOccurrenceOn()?->format('Y-m-d');
        $occurrenceMoved = $occurrenceOn !== $previous?->getRecurringOccurrenceOn()?->format('Y-m-d');
        $recurringChanged = $occurrenceMoved
            || (string) $series?->getId() !== (string) $previous?->getRecurringOperation()?->getId();

        if ($recurringChanged && null === $series && null === $previous?->getRecurringOperation()) {
            throw new UnprocessableEntityHttpException('recurringOccurrenceOn moves a line within its recurring operation: name the recurringOperation to attach it.');
        }

        // The category too: re-sending the one a rule or the series gave must
        // not seal it as typed by hand, which would shut the line out of the
        // series' category for good. Without the previous state, as before.
        $category = $data->getCategory();
        $categoryChanged = null === $previous
            || (string) $category?->getId() !== (string) $previous->getCategory()?->getId();

        $clearFields = $categoryChanged && null === $category ? ['categoryId'] : [];
        if ($recurringChanged && null === $series) {
            $clearFields[] = 'recurringOperation';
        }

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
            categoryId: $categoryChanged && null !== $category ? (string) $category->getId() : null,
            retrospect: $data->getRetrospect()->value,
            recurringOperationId: $recurringChanged && null !== $series ? (string) $series->getId() : null,
            // Left as it was, the occurrence is the one nearest the booking day.
            recurringOccurrenceOn: $occurrenceMoved && null !== $series ? $occurrenceOn : null,
            // A null category after the merge-patch is an explicit clear
            clearFields: $clearFields,
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
            if ($cause instanceof RecurringAttachmentException) {
                throw new UnprocessableEntityHttpException($cause->getMessage(), $e);
            }

            throw $e;
        }
    }
}
