<?php

declare(strict_types=1);

namespace Maggie\Finance\State;

use ApiPlatform\Metadata\Operation;
use ApiPlatform\State\ProcessorInterface;
use Maggie\Finance\Entity\Placement;
use Maggie\Finance\Message\UpdatePlacementCommand;
use Symfony\Component\Messenger\MessageBusInterface;
use Symfony\Component\Messenger\Stamp\HandledStamp;

/**
 * The merge-patch has already been applied to $data: it is the whole state
 * wanted, so every field is sent.
 *
 * @implements ProcessorInterface<Placement, Placement>
 */
class UpdatePlacementProcessor implements ProcessorInterface
{
    use DispatchesProjectCommandTrait;

    public function __construct(
        private readonly MessageBusInterface $bus,
    ) {
    }

    public function process(mixed $data, Operation $operation, array $uriVariables = [], array $context = []): Placement
    {
        return $this->dispatchProjectCommand($this->bus, new UpdatePlacementCommand(
            userId: (string) $data->getUser()->getId(),
            placementId: (string) $data->getId(),
            name: $data->getName(),
            initial: $data->getInitial(),
            deposit: $data->getDeposit(),
            frequency: $data->getFrequency()->value,
            annualRate: $data->getAnnualRate(),
            years: $data->getYears(),
        ))->last(HandledStamp::class)->getResult();
    }
}
