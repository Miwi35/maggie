<?php

declare(strict_types=1);

namespace Maggie\Finance\State;

use ApiPlatform\Metadata\Operation;
use ApiPlatform\State\ProcessorInterface;
use Maggie\Finance\Entity\Category;
use Maggie\Finance\Message\DeleteCategoryCommand;
use Symfony\Component\Messenger\MessageBusInterface;

/** @implements ProcessorInterface<Category, void> */
class DeleteCategoryProcessor implements ProcessorInterface
{
    public function __construct(
        private readonly MessageBusInterface $bus,
    ) {
    }

    public function process(mixed $data, Operation $operation, array $uriVariables = [], array $context = []): void
    {
        $this->bus->dispatch(new DeleteCategoryCommand(
            userId: (string) $data->getUser()->getId(),
            categoryId: (string) $data->getId(),
        ));
    }
}
