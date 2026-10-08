<?php

declare(strict_types=1);

namespace Maggie\Grocery\State;

use ApiPlatform\Metadata\Operation;
use ApiPlatform\State\ProcessorInterface;
use Maggie\Core\Entity\User;
use Maggie\Grocery\Entity\Product;
use Maggie\Grocery\Message\CreateProductCommand;
use Symfony\Bundle\SecurityBundle\Security;
use Symfony\Component\Messenger\MessageBusInterface;
use Symfony\Component\Messenger\Stamp\HandledStamp;

/** @implements ProcessorInterface<Product, Product> */
class CreateProductProcessor implements ProcessorInterface
{
    use DispatchesProductCommandTrait;

    public function __construct(
        private readonly MessageBusInterface $bus,
        private readonly Security $security,
    ) {
    }

    public function process(mixed $data, Operation $operation, array $uriVariables = [], array $context = []): Product
    {
        /** @var User $user */
        $user = $this->security->getUser();

        $envelope = $this->dispatchProductCommand(new CreateProductCommand(
            userId: (string) $user->getId(),
            name: $data->getName(),
            category: $data->getCategory()->value,
            defaultUnit: $data->getDefaultUnit()?->value,
            preferredStoreId: null !== $data->getPreferredStore() ? (string) $data->getPreferredStore()->getId() : null,
            fallbackStoreId: null !== $data->getFallbackStore() ? (string) $data->getFallbackStore()->getId() : null,
            shelfLifeDays: $data->getShelfLifeDays(),
            packagingUnit: $data->getPackagingUnit()?->value,
            packagingSize: $data->getPackagingSize(),
            packagingSizeUnit: $data->getPackagingSizeUnit()?->value,
            stockState: $data->getStockState()->value,
            restockQuantity: $data->getRestockQuantity(),
            autoRestock: $data->isAutoRestock(),
        ));

        return $envelope->last(HandledStamp::class)->getResult();
    }
}
