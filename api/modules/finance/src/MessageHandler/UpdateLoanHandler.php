<?php

declare(strict_types=1);

namespace Maggie\Finance\MessageHandler;

use Maggie\Finance\Entity\Loan;
use Maggie\Finance\Message\UpdateLoanCommand;
use Maggie\Finance\Repository\LoanRepository;
use Maggie\Finance\UseCase\UpdateLoan;
use Symfony\Component\Messenger\Attribute\AsMessageHandler;

#[AsMessageHandler]
class UpdateLoanHandler
{
    public function __construct(
        private readonly UpdateLoan $updateLoan,
        private readonly LoanRepository $loanRepository,
    ) {
    }

    public function __invoke(UpdateLoanCommand $command): Loan
    {
        $loan = $this->loanRepository->findOneBy(['id' => $command->loanId, 'user' => $command->userId])
            ?? throw new \DomainException("Loan not found: {$command->loanId}");

        if (null !== $command->name) {
            $loan->setName($command->name);
        }
        if (null !== $command->lender) {
            $loan->setLender('' === $command->lender ? null : $command->lender);
        } elseif ($command->clears('lender')) {
            $loan->setLender(null);
        }
        if (null !== $command->principalRemainingCents) {
            if ($command->principalRemainingCents < 0) {
                throw new \DomainException('The remaining capital cannot be negative.');
            }
            $loan->setPrincipalRemainingCents($command->principalRemainingCents);
        }
        if (null !== $command->monthlyPaymentCents) {
            if ($command->monthlyPaymentCents <= 0) {
                throw new \DomainException('The monthly payment must be positive.');
            }
            $loan->setMonthlyPaymentCents($command->monthlyPaymentCents);
        }
        if (null !== $command->annualRateBasisPoints) {
            if ($command->annualRateBasisPoints < 0) {
                throw new \DomainException('The rate cannot be negative.');
            }
            $loan->setAnnualRateBasisPoints($command->annualRateBasisPoints);
        }
        if (null !== $command->priority) {
            $loan->setPriority($command->priority);
        }
        if (null !== $command->currency) {
            $loan->setCurrency($command->currency);
        }

        if ($loan->getPrincipalRemainingCents() > 0 && !$loan->amortises()) {
            throw new \DomainException('The monthly payment does not cover the interest: this loan would never be repaid.');
        }

        return $this->updateLoan->execute($loan);
    }
}
