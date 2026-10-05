<?php

declare(strict_types=1);

namespace Maggie\Grocery\State;

use ApiPlatform\Metadata\Operation;
use ApiPlatform\State\ProcessorInterface;
use Maggie\Grocery\Entity\Product;
use Maggie\Grocery\Message\UpdateProductCommand;
use Symfony\Component\Messenger\MessageBusInterface;
use Symfony\Component\Messenger\Stamp\HandledStamp;

/** @implements ProcessorInterface<Product, Product> */
class UpdateProductProcessor implements ProcessorInterface
{
    public function __construct(
        private readonly MessageBusInterface $bus,
    ) {
    }

    public function process(mixed $data, Operation $operation, array $uriVariables = [], array $context = []): Product
    {
        /** @var Product|null $previous */
        $previous = $context['previous_data'] ?? null;

        // A nullable field that was set and is now null is an explicit clear
        $clearFields = [];
        if (null !== $previous && null === $data->getDefaultUnit() && null !== $previous->getDefaultUnit()) {
            $clearFields[] = 'defaultUnit';
        }
        if (null !== $previous && null === $data->getPreferredStore() && null !== $previous->getPreferredStore()) {
            $clearFields[] = 'preferredStore';
        }
        if (null !== $previous && null === $data->getFallbackStore() && null !== $previous->getFallbackStore()) {
            $clearFields[] = 'fallbackStore';
        }
        if (null !== $previous && null === $data->getShelfLifeDays() && null !== $previous->getShelfLifeDays()) {
            $clearFields[] = 'shelfLifeDays';
        }

        $envelope = $this->bus->dispatch(new UpdateProductCommand(
            productId: (string) $data->getId(),
            name: $data->getName(),
            category: $data->getCategory()->value,
            defaultUnit: $data->getDefaultUnit()?->value,
            preferredStoreId: null !== $data->getPreferredStore() ? (string) $data->getPreferredStore()->getId() : null,
            fallbackStoreId: null !== $data->getFallbackStore() ? (string) $data->getFallbackStore()->getId() : null,
            shelfLifeDays: $data->getShelfLifeDays(),
            clearFields: $clearFields,
        ));

        return $envelope->last(HandledStamp::class)->getResult();
    }
}
