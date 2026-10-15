<?php

declare(strict_types=1);

namespace Maggie\Finance\State;

use ApiPlatform\Metadata\Operation;
use ApiPlatform\State\ProcessorInterface;
use Maggie\Finance\Entity\RecurringOperation;
use Maggie\Finance\Message\UpdateRecurringOperationCommand;
use Symfony\Component\Messenger\MessageBusInterface;
use Symfony\Component\Messenger\Stamp\HandledStamp;

/** @implements ProcessorInterface<RecurringOperation, RecurringOperation> */
class UpdateRecurringOperationProcessor implements ProcessorInterface
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
            'counterpartyName' => $data->getCounterpartyName(),
            'labelPattern' => $data->getLabelPattern(),
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

        return $this->bus->dispatch(new UpdateRecurringOperationCommand(
            userId: (string) $data->getUser()->getId(),
            recurringOperationId: (string) $data->getId(),
            label: $data->getLabel(),
            categoryId: (string) $data->getCategory()->getId(),
            accountId: (string) $data->getAccount()->getId(),
            referenceAmountCents: $data->getReferenceAmountCents(),
            anchorOn: $data->getAnchorOn()->format('Y-m-d'),
            counterpartyName: $data->getCounterpartyName(),
            labelPattern: $data->getLabelPattern(),
            period: $data->getPeriod()->value,
            dayRule: $data->getDayRule()->value,
            referenceSource: $data->getReferenceSource()->value,
            amountTolerancePercent: $data->getAmountTolerancePercent(),
            dateToleranceDays: $data->getDateToleranceDays(),
            endsOn: $data->getEndsOn()?->format('Y-m-d'),
            clearFields: $clearFields,
            qualification: $qualificationChanged ? $qualification : null,
        ))->last(HandledStamp::class)->getResult();
    }
}
