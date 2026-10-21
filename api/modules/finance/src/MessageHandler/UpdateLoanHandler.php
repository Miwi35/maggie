<?php

declare(strict_types=1);

namespace Maggie\Finance\MessageHandler;

use Doctrine\ORM\EntityManagerInterface;
use Maggie\Finance\Entity\Loan;
use Maggie\Finance\Message\UpdateLoanCommand;
use Maggie\Finance\Repository\LoanRepository;
use Maggie\Finance\Service\LoanDetails;
use Maggie\Finance\UseCase\UpdateLoan;
use Symfony\Component\Messenger\Attribute\AsMessageHandler;

#[AsMessageHandler]
class UpdateLoanHandler
{
    public function __construct(
        private readonly UpdateLoan $updateLoan,
        private readonly LoanRepository $loanRepository,
        private readonly EntityManagerInterface $em,
    ) {
    }

    public function __invoke(UpdateLoanCommand $command): Loan
    {
        $loan = $this->loanRepository->findOneBy(['id' => $command->loanId, 'user' => $command->userId])
            ?? throw new \DomainException("Loan not found: {$command->loanId}");

        try {
            $this->apply($loan, $command);
        } catch (\DomainException $e) {
            // Refused: the loan keeps the state it had, even in memory.
            $this->em->refresh($loan);

            throw $e;
        }

        return $this->updateLoan->execute($loan);
    }

    private function apply(Loan $loan, UpdateLoanCommand $command): void
    {
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

        LoanDetails::apply($loan, $command->details);

        CreateLoanHandler::assertConditionsHold($loan);
    }
}
