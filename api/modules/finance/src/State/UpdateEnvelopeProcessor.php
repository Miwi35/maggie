<?php

declare(strict_types=1);

namespace Maggie\Finance\State;

use ApiPlatform\Metadata\Operation;
use ApiPlatform\State\ProcessorInterface;
use Maggie\Finance\Entity\Envelope;
use Maggie\Finance\Message\UpdateEnvelopeCommand;
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
        $stamped = $this->bus->dispatch(new UpdateEnvelopeCommand(
            envelopeId: (string) $data->getId(),
            categoryId: (string) $data->getCategory()->getId(),
            amountCents: $data->getAmountCents(),
            year: $data->getYear(),
            mode: $data->getMode()->value,
            month: $data->getMonth(),
            currency: $data->getCurrency(),
        ));

        return $stamped->last(HandledStamp::class)->getResult();
    }
}
