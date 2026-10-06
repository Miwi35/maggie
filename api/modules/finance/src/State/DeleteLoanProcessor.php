<?php

declare(strict_types=1);

namespace Maggie\Finance\State;

use ApiPlatform\Metadata\Operation;
use ApiPlatform\State\ProcessorInterface;
use Maggie\Finance\Entity\Loan;
use Maggie\Finance\Message\DeleteLoanCommand;
use Symfony\Component\Messenger\MessageBusInterface;

/** @implements ProcessorInterface<Loan, void> */
class DeleteLoanProcessor implements ProcessorInterface
{
    public function __construct(
        private readonly MessageBusInterface $bus,
    ) {
    }

    public function process(mixed $data, Operation $operation, array $uriVariables = [], array $context = []): void
    {
        $this->bus->dispatch(new DeleteLoanCommand(
            userId: (string) $data->getUser()->getId(),
            loanId: (string) $data->getId(),
        ));
    }
}
