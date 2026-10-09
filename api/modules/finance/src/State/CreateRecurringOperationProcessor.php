<?php

declare(strict_types=1);

namespace Maggie\Finance\State;

use ApiPlatform\Metadata\Operation;
use ApiPlatform\State\ProcessorInterface;
use Maggie\Core\Entity\User;
use Maggie\Finance\Entity\RecurringOperation;
use Maggie\Finance\Message\CreateRecurringOperationCommand;
use Symfony\Bundle\SecurityBundle\Security;
use Symfony\Component\Messenger\MessageBusInterface;

/** @implements ProcessorInterface<RecurringOperation, RecurringOperation> */
class CreateRecurringOperationProcessor implements ProcessorInterface
{
    use DispatchesRecurringOperationTrait;

    public function __construct(
        private readonly MessageBusInterface $bus,
        private readonly Security $security,
    ) {
    }

    public function process(mixed $data, Operation $operation, array $uriVariables = [], array $context = []): RecurringOperation
    {
        /** @var User $user */
        $user = $this->security->getUser();

        return $this->dispatchForResult($this->bus, new CreateRecurringOperationCommand(
            userId: (string) $user->getId(),
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
        ));
    }
}
