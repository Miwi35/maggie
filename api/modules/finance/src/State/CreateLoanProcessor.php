<?php

declare(strict_types=1);

namespace Maggie\Finance\State;

use ApiPlatform\Metadata\Operation;
use ApiPlatform\State\ProcessorInterface;
use Maggie\Core\Entity\User;
use Maggie\Finance\Entity\Loan;
use Maggie\Finance\Message\CreateLoanCommand;
use Symfony\Bundle\SecurityBundle\Security;
use Symfony\Component\Messenger\MessageBusInterface;
use Symfony\Component\Messenger\Stamp\HandledStamp;

/** @implements ProcessorInterface<Loan, Loan> */
class CreateLoanProcessor implements ProcessorInterface
{
    public function __construct(
        private readonly MessageBusInterface $bus,
        private readonly Security $security,
    ) {
    }

    public function process(mixed $data, Operation $operation, array $uriVariables = [], array $context = []): Loan
    {
        /** @var User $user */
        $user = $this->security->getUser();

        $stamped = $this->bus->dispatch(new CreateLoanCommand(
            userId: (string) $user->getId(),
            name: $data->getName(),
            principalRemainingCents: $data->getPrincipalRemainingCents(),
            monthlyPaymentCents: $data->getMonthlyPaymentCents(),
            annualRateBasisPoints: $data->getAnnualRateBasisPoints(),
            lender: $data->getLender(),
            priority: $data->getPriority(),
            currency: $data->getCurrency(),
        ));

        return $stamped->last(HandledStamp::class)->getResult();
    }
}
