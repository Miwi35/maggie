<?php

declare(strict_types=1);

namespace Maggie\Finance\State;

use ApiPlatform\Metadata\Operation;
use ApiPlatform\State\ProcessorInterface;
use Maggie\Finance\Entity\Loan;
use Maggie\Finance\Message\UpdateLoanCommand;
use Symfony\Component\Messenger\MessageBusInterface;
use Symfony\Component\Messenger\Stamp\HandledStamp;

/** @implements ProcessorInterface<Loan, Loan> */
class UpdateLoanProcessor implements ProcessorInterface
{
    public function __construct(
        private readonly MessageBusInterface $bus,
    ) {
    }

    public function process(mixed $data, Operation $operation, array $uriVariables = [], array $context = []): Loan
    {
        $stamped = $this->bus->dispatch(new UpdateLoanCommand(
            loanId: (string) $data->getId(),
            name: $data->getName(),
            principalRemainingCents: $data->getPrincipalRemainingCents(),
            monthlyPaymentCents: $data->getMonthlyPaymentCents(),
            annualRateBasisPoints: $data->getAnnualRateBasisPoints(),
            lender: $data->getLender(),
            priority: $data->getPriority(),
            currency: $data->getCurrency(),
            // A null lender after the merge-patch is an explicit clear
            clearFields: null === $data->getLender() ? ['lender'] : [],
        ));

        return $stamped->last(HandledStamp::class)->getResult();
    }
}
