<?php

declare(strict_types=1);

namespace Maggie\Finance\MessageHandler;

use Maggie\Finance\Message\DeleteLoanCommand;
use Maggie\Finance\Repository\LoanRepository;
use Maggie\Finance\UseCase\DeleteLoan;
use Symfony\Component\Messenger\Attribute\AsMessageHandler;

#[AsMessageHandler]
class DeleteLoanHandler
{
    public function __construct(
        private readonly DeleteLoan $deleteLoan,
        private readonly LoanRepository $loanRepository,
    ) {
    }

    public function __invoke(DeleteLoanCommand $command): void
    {
        $loan = $this->loanRepository->find($command->loanId)
            ?? throw new \DomainException("Loan not found: {$command->loanId}");

        $this->deleteLoan->execute($loan);
    }
}
