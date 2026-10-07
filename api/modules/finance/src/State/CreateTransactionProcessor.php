<?php

declare(strict_types=1);

namespace Maggie\Finance\State;

use ApiPlatform\Metadata\Operation;
use ApiPlatform\State\ProcessorInterface;
use Maggie\Core\Entity\User;
use Maggie\Finance\Entity\Transaction;
use Maggie\Finance\Exception\IncompatibleCategoryException;
use Maggie\Finance\Message\CreateTransactionCommand;
use Symfony\Bundle\SecurityBundle\Security;
use Symfony\Component\HttpKernel\Exception\BadRequestHttpException;
use Symfony\Component\Messenger\Envelope;
use Symfony\Component\Messenger\Exception\HandlerFailedException;
use Symfony\Component\Messenger\MessageBusInterface;
use Symfony\Component\Messenger\Stamp\HandledStamp;

/** @implements ProcessorInterface<Transaction, Transaction> */
class CreateTransactionProcessor implements ProcessorInterface
{
    public function __construct(
        private readonly MessageBusInterface $bus,
        private readonly Security $security,
    ) {
    }

    public function process(mixed $data, Operation $operation, array $uriVariables = [], array $context = []): Transaction
    {
        /** @var User $user */
        $user = $this->security->getUser();

        $envelope = $this->dispatch(new CreateTransactionCommand(
            userId: (string) $user->getId(),
            accountId: (string) $data->getAccount()->getId(),
            amountCents: $data->getAmountCents(),
            label: $data->getLabel(),
            bookedAt: $data->getBookedAt()->format(\DateTimeInterface::ATOM),
            status: $data->getStatus()->value,
            currency: $data->getCurrency(),
            isExceptional: $data->isExceptional(),
            categoryId: null !== $data->getCategory() ? (string) $data->getCategory()->getId() : null,
        ));

        return $envelope->last(HandledStamp::class)->getResult();
    }

    private function dispatch(object $command): Envelope
    {
        try {
            return $this->bus->dispatch($command);
        } catch (HandlerFailedException $e) {
            $cause = $e->getPrevious();
            if ($cause instanceof IncompatibleCategoryException) {
                throw new BadRequestHttpException($cause->getMessage(), $e);
            }

            throw $e;
        }
    }
}
