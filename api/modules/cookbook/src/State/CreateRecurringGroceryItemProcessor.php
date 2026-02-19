<?php

declare(strict_types=1);

namespace Maggie\Cookbook\State;

use ApiPlatform\Metadata\Operation;
use ApiPlatform\State\ProcessorInterface;
use Maggie\Cookbook\Entity\RecurringGroceryItem;
use Maggie\Cookbook\Message\CreateRecurringGroceryItemCommand;
use Maggie\Core\Entity\User;
use Symfony\Bundle\SecurityBundle\Security;
use Symfony\Component\Messenger\MessageBusInterface;
use Symfony\Component\Messenger\Stamp\HandledStamp;

/** @implements ProcessorInterface<RecurringGroceryItem, RecurringGroceryItem> */
class CreateRecurringGroceryItemProcessor implements ProcessorInterface
{
    public function __construct(
        private readonly MessageBusInterface $bus,
        private readonly Security $security,
    ) {
    }

    public function process(mixed $data, Operation $operation, array $uriVariables = [], array $context = []): RecurringGroceryItem
    {
        /** @var User $user */
        $user = $this->security->getUser();

        $envelope = $this->bus->dispatch(new CreateRecurringGroceryItemCommand(
            userId: (string) $user->getId(),
            frequency: $data->getFrequency()->value,
            productId: $data->getProduct() !== null ? (string) $data->getProduct()->getId() : null,
            customLabel: $data->getCustomLabel(),
            quantity: $data->getQuantity(),
            unit: $data->getUnit()?->value,
        ));

        return $envelope->last(HandledStamp::class)->getResult();
    }
}
