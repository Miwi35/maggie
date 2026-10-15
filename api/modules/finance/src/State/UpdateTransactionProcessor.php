<?php

declare(strict_types=1);

namespace Maggie\Finance\State;

use ApiPlatform\Metadata\Operation;
use ApiPlatform\State\ProcessorInterface;
use Maggie\Finance\Entity\Transaction;
use Maggie\Finance\Exception\IncompatibleCategoryException;
use Maggie\Finance\Exception\OneOffAttributionException;
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

        // The qualification likewise: only a change is the owner's own.
        $qualification = $data->getQualification();
        $qualificationChanged = null !== $previous
            && $qualification->toArray() !== $previous->getQualification()->toArray();

        $clearFields = $categoryChanged && null === $category ? ['categoryId'] : [];
        if ($recurringChanged && null === $series) {
            $clearFields[] = 'recurringOperation';
        }
        $oneOff = $data->getOneOffEnvelope();
        $oneOffChanged = (string) $oneOff?->getId() !== (string) $previous?->getOneOffEnvelope()?->getId();
        if ($oneOffChanged && null === $oneOff) {
            $clearFields[] = 'oneOffEnvelope';
        }
        $accountId = (string) $data->getAccount()->getId();

        // API Platform reads every IRI of the PATCH through the item provider —
        // Elasticsearch, whose copies Doctrine does not manage and would take
        // for new entities on flush. The changes are known by now and go to the
        // handler as ids, which it resolves itself: the line keeps the stored
        // relations until then.
        if (null !== $previous) {
            $data->setAccount($previous->getAccount());
            $data->setCategory($previous->getCategory());
            $data->setRecurringOperation($previous->getRecurringOperation());
            $data->setOneOffEnvelope($previous->getOneOffEnvelope());
        }

        // The merged line carries the note whether or not the PATCH named it: null is an explicit clear.
        if (null === $data->getNote()) {
            $clearFields[] = 'note';
        }

        $envelope = $this->dispatch(new UpdateTransactionCommand(
            userId: (string) $data->getUser()->getId(),
            transactionId: (string) $data->getId(),
            accountId: $accountId,
            amountCents: $data->getAmountCents(),
            label: $data->getLabel(),
            bookedAt: $data->getBookedAt()->format(\DateTimeInterface::ATOM),
            status: $data->getStatus()->value,
            currency: $data->getCurrency(),
            isExceptional: $data->isExceptional(),
            categoryId: $categoryChanged && null !== $category ? (string) $category->getId() : null,
            qualification: $qualificationChanged ? $qualification->toArray() : null,
            retrospect: $data->getRetrospect()->value,
            recurringOperationId: $recurringChanged && null !== $series ? (string) $series->getId() : null,
            // Left as it was, the occurrence is the one nearest the booking day.
            recurringOccurrenceOn: $occurrenceMoved && null !== $series ? $occurrenceOn : null,
            note: $data->getNote(),
            oneOffEnvelopeId: $oneOffChanged && null !== $oneOff ? (string) $oneOff->getId() : null,
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
            if ($cause instanceof IncompatibleCategoryException || $cause instanceof OneOffAttributionException) {
                throw new BadRequestHttpException($cause->getMessage(), $e);
            }
            if ($cause instanceof RecurringAttachmentException) {
                throw new UnprocessableEntityHttpException($cause->getMessage(), $e);
            }

            throw $e;
        }
    }
}
