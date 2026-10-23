<?php

declare(strict_types=1);

namespace Maggie\Finance\State;

use ApiPlatform\Metadata\Operation;
use ApiPlatform\State\ProcessorInterface;
use Maggie\Core\Entity\User;
use Maggie\Finance\Entity\Placement;
use Maggie\Finance\Message\CreatePlacementCommand;
use Symfony\Bundle\SecurityBundle\Security;
use Symfony\Component\Messenger\MessageBusInterface;
use Symfony\Component\Messenger\Stamp\HandledStamp;

/** @implements ProcessorInterface<Placement, Placement> */
class CreatePlacementProcessor implements ProcessorInterface
{
    use DispatchesProjectCommandTrait;

    public function __construct(
        private readonly MessageBusInterface $bus,
        private readonly Security $security,
    ) {
    }

    public function process(mixed $data, Operation $operation, array $uriVariables = [], array $context = []): Placement
    {
        /** @var User $user */
        $user = $this->security->getUser();

        return $this->dispatchProjectCommand($this->bus, new CreatePlacementCommand(
            userId: (string) $user->getId(),
            name: $data->getName(),
            initial: $data->getInitial(),
            deposit: $data->getDeposit(),
            frequency: $data->getFrequency()->value,
            annualRate: $data->getAnnualRate(),
            years: $data->getYears(),
        ))->last(HandledStamp::class)->getResult();
    }
}
