<?php

declare(strict_types=1);

namespace Maggie\Finance\State;

use ApiPlatform\Metadata\Operation;
use ApiPlatform\State\ProcessorInterface;
use Maggie\Finance\Entity\Envelope;
use Maggie\Finance\Message\DeleteEnvelopeCommand;
use Symfony\Component\Messenger\MessageBusInterface;

/** @implements ProcessorInterface<Envelope, void> */
class DeleteEnvelopeProcessor implements ProcessorInterface
{
    public function __construct(
        private readonly MessageBusInterface $bus,
    ) {
    }

    public function process(mixed $data, Operation $operation, array $uriVariables = [], array $context = []): void
    {
        $this->bus->dispatch(new DeleteEnvelopeCommand(
            envelopeId: (string) $data->getId(),
        ));
    }
}
