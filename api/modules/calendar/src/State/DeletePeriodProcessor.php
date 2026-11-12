<?php

declare(strict_types=1);

namespace Maggie\Calendar\State;

use ApiPlatform\Metadata\Operation;
use ApiPlatform\State\ProcessorInterface;
use Maggie\Calendar\Entity\Period;
use Maggie\Calendar\Message\DeletePeriodCommand;
use Maggie\Core\Entity\User;
use Symfony\Bundle\SecurityBundle\Security;
use Symfony\Component\Messenger\MessageBusInterface;

/** @implements ProcessorInterface<Period, void> */
class DeletePeriodProcessor implements ProcessorInterface
{
    use DispatchesPeriodCommandTrait;

    public function __construct(
        private readonly MessageBusInterface $bus,
        private readonly Security $security,
    ) {
    }

    public function process(mixed $data, Operation $operation, array $uriVariables = [], array $context = []): void
    {
        /** @var User $user */
        $user = $this->security->getUser();

        $this->dispatchPeriodCommand($this->bus, new DeletePeriodCommand(
            userId: (string) $user->getId(),
            periodId: (string) $data->getId(),
        ));
    }
}
