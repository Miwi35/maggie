<?php

declare(strict_types=1);

namespace Maggie\Grocery\State;

use ApiPlatform\Metadata\Operation;
use ApiPlatform\State\ProcessorInterface;
use Maggie\Core\Entity\User;
use Maggie\Grocery\Entity\Aisle;
use Maggie\Grocery\Message\CreateAisleCommand;
use Symfony\Bundle\SecurityBundle\Security;
use Symfony\Component\HttpKernel\Exception\BadRequestHttpException;
use Symfony\Component\Messenger\MessageBusInterface;
use Symfony\Component\Messenger\Stamp\HandledStamp;

/** @implements ProcessorInterface<Aisle, Aisle> */
class CreateAisleProcessor implements ProcessorInterface
{
    use DispatchesAisleCommandTrait;

    public function __construct(
        private readonly MessageBusInterface $bus,
        private readonly Security $security,
    ) {
    }

    public function process(mixed $data, Operation $operation, array $uriVariables = [], array $context = []): Aisle
    {
        /** @var User $user */
        $user = $this->security->getUser();

        $store = $data->sentStore() ?? throw new BadRequestHttpException('An aisle needs a store.');

        $envelope = $this->dispatchAisleCommand(new CreateAisleCommand(
            userId: (string) $user->getId(),
            storeId: (string) $store->getId(),
            name: $data->getName(),
            position: $data->getPosition() > 0 ? $data->getPosition() : null,
        ));

        return $envelope->last(HandledStamp::class)->getResult();
    }
}
