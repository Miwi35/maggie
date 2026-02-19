<?php

declare(strict_types=1);

namespace Maggie\Cookbook\State;

use ApiPlatform\Metadata\Operation;
use ApiPlatform\State\ProcessorInterface;
use Maggie\Cookbook\Entity\GroceryList;
use Maggie\Cookbook\Message\CreateGroceryListCommand;
use Maggie\Core\Entity\User;
use Symfony\Bundle\SecurityBundle\Security;
use Symfony\Component\Messenger\MessageBusInterface;
use Symfony\Component\Messenger\Stamp\HandledStamp;

/** @implements ProcessorInterface<GroceryList, GroceryList> */
class CreateGroceryListProcessor implements ProcessorInterface
{
    public function __construct(
        private readonly MessageBusInterface $bus,
        private readonly Security $security,
    ) {
    }

    public function process(mixed $data, Operation $operation, array $uriVariables = [], array $context = []): GroceryList
    {
        /** @var User $user */
        $user = $this->security->getUser();

        $envelope = $this->bus->dispatch(new CreateGroceryListCommand(
            userId: (string) $user->getId(),
            weekStart: $data->getWeekStart()->format('Y-m-d'),
            status: $data->getStatus()->value,
        ));

        return $envelope->last(HandledStamp::class)->getResult();
    }
}
