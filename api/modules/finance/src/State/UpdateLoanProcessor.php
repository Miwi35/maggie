<?php

declare(strict_types=1);

namespace Maggie\Finance\State;

use ApiPlatform\Metadata\Operation;
use ApiPlatform\State\ProcessorInterface;
use Maggie\Finance\Entity\Loan;
use Maggie\Finance\Message\UpdateLoanCommand;
use Maggie\Finance\Service\LoanDetails;
use Symfony\Component\Messenger\MessageBusInterface;
use Symfony\Component\Messenger\Stamp\HandledStamp;

/** @implements ProcessorInterface<Loan, Loan> */
class UpdateLoanProcessor implements ProcessorInterface
{
    use DispatchesProjectCommandTrait;

    public function __construct(
        private readonly MessageBusInterface $bus,
    ) {
    }

    public function process(mixed $data, Operation $operation, array $uriVariables = [], array $context = []): Loan
    {
        $stamped = $this->dispatchProjectCommand($this->bus, new UpdateLoanCommand(
            userId: (string) $data->getUser()->getId(),
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
            // The whole state again: a null condition is emptied.
            details: LoanDetails::of($data),
        ));

        return $stamped->last(HandledStamp::class)->getResult();
    }
}
