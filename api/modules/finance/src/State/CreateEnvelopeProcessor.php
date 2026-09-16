<?php

declare(strict_types=1);

namespace Maggie\Finance\State;

use ApiPlatform\Metadata\Operation;
use ApiPlatform\State\ProcessorInterface;
use Maggie\Core\Entity\User;
use Maggie\Finance\Entity\Envelope;
use Maggie\Finance\Message\CreateEnvelopeCommand;
use Symfony\Bundle\SecurityBundle\Security;
use Symfony\Component\Messenger\MessageBusInterface;
use Symfony\Component\Messenger\Stamp\HandledStamp;

/** @implements ProcessorInterface<Envelope, Envelope> */
class CreateEnvelopeProcessor implements ProcessorInterface
{
    public function __construct(
        private readonly MessageBusInterface $bus,
        private readonly Security $security,
    ) {
    }

    public function process(mixed $data, Operation $operation, array $uriVariables = [], array $context = []): Envelope
    {
        /** @var User $user */
        $user = $this->security->getUser();

        $stamped = $this->bus->dispatch(new CreateEnvelopeCommand(
            userId: (string) $user->getId(),
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
