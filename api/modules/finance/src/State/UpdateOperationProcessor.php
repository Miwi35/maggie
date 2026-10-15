<?php

declare(strict_types=1);

namespace Maggie\Finance\State;

use ApiPlatform\Metadata\Operation;
use ApiPlatform\State\ProcessorInterface;
use Maggie\Finance\Entity\RecurringOperation;
use Maggie\Finance\Message\UpdateOperationCommand;
use Symfony\Component\HttpKernel\Exception\BadRequestHttpException;
use Symfony\Component\Messenger\Envelope;
use Symfony\Component\Messenger\Exception\HandlerFailedException;
use Symfony\Component\Messenger\MessageBusInterface;
use Symfony\Component\Messenger\Stamp\HandledStamp;

/** @implements ProcessorInterface<RecurringOperation, RecurringOperation> */
class UpdateOperationProcessor implements ProcessorInterface
{
    public function __construct(
        private readonly MessageBusInterface $bus,
    ) {
    }

    public function process(mixed $data, Operation $operation, array $uriVariables = [], array $context = []): RecurringOperation
    {
        // A nullable field that is null after the merge-patch is an explicit clear
        $clearFields = [];
        foreach ([
            'label' => $data->getLabel(),
            'counterpartyName' => $data->getCounterpartyName(),
            'labelPattern' => $data->getLabelPattern(),
            'minAmountCents' => $data->getMinAmountCents(),
            'maxAmountCents' => $data->getMaxAmountCents(),
            'endsOn' => $data->getEndsOn(),
        ] as $field => $value) {
            if (null === $value) {
                $clearFields[] = $field;
            }
        }

        // Sent when it changed, or with a new category: the parameters the
        // owner sees stay theirs, not those of the category's deprecated flag.
        $previous = $context['previous_data'] ?? null;
        $qualification = $data->getQualification()->toArray();
        $qualificationChanged = $previous instanceof RecurringOperation
            && ($qualification !== $previous->getQualification()->toArray()
                || (string) $data->getCategory()->getId() !== (string) $previous->getCategory()->getId());

        return $this->dispatch(new UpdateOperationCommand(
            userId: (string) $data->getUser()->getId(),
            operationId: (string) $data->getId(),
            categoryId: (string) $data->getCategory()->getId(),
            label: $data->getLabel(),
            counterpartyName: $data->getCounterpartyName(),
            labelPattern: $data->getLabelPattern(),
            matchType: $data->getMatchType()->value,
            direction: $data->getDirection()->value,
            minAmountCents: $data->getMinAmountCents(),
            maxAmountCents: $data->getMaxAmountCents(),
            priority: $data->getPriority(),
            isActive: $data->isActive(),
            period: $data->getPeriod()?->value,
            anchorOn: $data->getAnchorOn()?->format('Y-m-d'),
            referenceAmountCents: $data->getReferenceAmountCents(),
            accountId: null !== $data->getAccount() ? (string) $data->getAccount()->getId() : null,
            dayRule: $data->getDayRule()->value,
            referenceSource: $data->getReferenceSource()->value,
            amountTolerancePercent: $data->getAmountTolerancePercent(),
            dateToleranceDays: $data->getDateToleranceDays(),
            endsOn: $data->getEndsOn()?->format('Y-m-d'),
            clearFields: $clearFields,
            applyToExisting: $data->shouldApplyToExisting(),
            qualification: $qualificationChanged ? $qualification : null,
        ))->last(HandledStamp::class)->getResult();
    }

    /** What the handler refuses — a reference not found, a recurrence changed — is the request's fault. */
    private function dispatch(object $command): Envelope
    {
        try {
            return $this->bus->dispatch($command);
        } catch (HandlerFailedException $e) {
            $cause = $e->getPrevious();
            if ($cause instanceof \DomainException) {
                throw new BadRequestHttpException($cause->getMessage(), $e);
            }

            throw $e;
        }
    }
}
