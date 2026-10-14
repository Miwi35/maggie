<?php

declare(strict_types=1);

namespace Maggie\Finance\State;

use ApiPlatform\Metadata\Operation;
use ApiPlatform\State\ProcessorInterface;
use Maggie\Finance\Entity\Envelope;
use Maggie\Finance\Message\UpdateEnvelopeCommand;
use Symfony\Component\HttpKernel\Exception\UnprocessableEntityHttpException;
use Symfony\Component\Messenger\MessageBusInterface;
use Symfony\Component\Messenger\Stamp\HandledStamp;

/** @implements ProcessorInterface<Envelope, Envelope> */
class UpdateEnvelopeProcessor implements ProcessorInterface
{
    public function __construct(
        private readonly MessageBusInterface $bus,
    ) {
    }

    public function process(mixed $data, Operation $operation, array $uriVariables = [], array $context = []): Envelope
    {
        /** @var Envelope|null $previous */
        $previous = $context['previous_data'] ?? null;
        if (null !== $previous && $previous->isOneOff() !== $data->isOneOff()) {
            throw new UnprocessableEntityHttpException('An envelope cannot change between one-off and monthly or annual: create a new one.');
        }

        $stamped = $this->bus->dispatch(new UpdateEnvelopeCommand(
            userId: (string) $data->getUser()->getId(),
            envelopeId: (string) $data->getId(),
            categoryId: null !== $data->getCategory() ? (string) $data->getCategory()->getId() : null,
            amountCents: $data->getAmountCents(),
            year: $data->getYear(),
            mode: $data->getMode()->value,
            month: $data->getMonth(),
            currency: $data->getCurrency(),
            name: $data->getName(),
        ));

        return $stamped->last(HandledStamp::class)->getResult();
    }
}
