<?php

declare(strict_types=1);

namespace Maggie\Finance\MessageHandler;

use Maggie\Core\Repository\UserRepository;
use Maggie\Finance\Entity\Loan;
use Maggie\Finance\Message\CreateLoanCommand;
use Maggie\Finance\Service\LoanDetails;
use Maggie\Finance\UseCase\CreateLoan;
use Symfony\Component\Messenger\Attribute\AsMessageHandler;

#[AsMessageHandler]
class CreateLoanHandler
{
    public function __construct(
        private readonly CreateLoan $createLoan,
        private readonly UserRepository $userRepository,
    ) {
    }

    public function __invoke(CreateLoanCommand $command): Loan
    {
        $user = $this->userRepository->find($command->userId)
            ?? throw new \DomainException('User not found.');

        $loan = new Loan();
        $loan->setUser($user);
        $loan->setName($command->name);
        $loan->setLender($command->lender);
        $loan->setPrincipalRemainingCents($command->principalRemainingCents);
        $loan->setMonthlyPaymentCents($command->monthlyPaymentCents);
        $loan->setAnnualRateBasisPoints($command->annualRateBasisPoints);
        $loan->setPriority($command->priority);
        $loan->setCurrency($command->currency);
        LoanDetails::apply($loan, $command->details);

        self::assertConditionsHold($loan);

        return $this->createLoan->execute($loan);
    }

    /** What the API's validator and the MCP tool both refuse, whatever the door. */
    public static function assertConditionsHold(Loan $loan): void
    {
        if ($loan->getMonthlyPaymentCents() <= 0) {
            throw new \DomainException('The monthly payment must be positive.');
        }

        if (null !== $problem = $loan->conditionsProblem()) {
            throw new \DomainException($problem);
        }

        if ($loan->getType()->mustAmortiseNow() && $loan->getPrincipalRemainingCents() > 0 && !$loan->amortises()) {
            throw new \DomainException('The monthly payment does not cover the interest: this loan would never be repaid.');
        }
    }
}
