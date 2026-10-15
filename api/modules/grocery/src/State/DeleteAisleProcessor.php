<?php

declare(strict_types=1);

namespace Maggie\Grocery\State;

use ApiPlatform\Metadata\Operation;
use ApiPlatform\State\ProcessorInterface;
use Maggie\Core\Entity\User;
use Maggie\Grocery\Entity\Aisle;
use Maggie\Grocery\Message\DeleteAisleCommand;
use Symfony\Bundle\SecurityBundle\Security;
use Symfony\Component\Messenger\MessageBusInterface;

/** @implements ProcessorInterface<Aisle, void> */
class DeleteAisleProcessor implements ProcessorInterface
{
    use DispatchesAisleCommandTrait;

    public function __construct(
        private readonly MessageBusInterface $bus,
        private readonly Security $security,
    ) {
    }

    public function process(mixed $data, Operation $operation, array $uriVariables = [], array $context = []): void
    {
        /** @var User $user */
        $user = $this->security->getUser();

        $this->dispatchAisleCommand(new DeleteAisleCommand(
            userId: (string) $user->getId(),
            aisleId: (string) $data->getId(),
        ));
    }
}
