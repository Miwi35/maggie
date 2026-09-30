<?php

declare(strict_types=1);

namespace Maggie\Finance\State;

use ApiPlatform\Metadata\Operation;
use ApiPlatform\State\ProcessorInterface;
use Maggie\Finance\Entity\Category;
use Maggie\Finance\Message\UpdateCategoryCommand;
use Symfony\Component\Messenger\MessageBusInterface;
use Symfony\Component\Messenger\Stamp\HandledStamp;

/** @implements ProcessorInterface<Category, Category> */
class UpdateCategoryProcessor implements ProcessorInterface
{
    public function __construct(
        private readonly MessageBusInterface $bus,
    ) {
    }

    public function process(mixed $data, Operation $operation, array $uriVariables = [], array $context = []): Category
    {
        // A nullable field that is null after the merge-patch is an explicit clear
        $clearFields = [];
        foreach ([
            'parentId' => $data->getParent(),
            'color' => $data->getColor(),
            'icon' => $data->getIcon(),
        ] as $field => $value) {
            if ($value === null) {
                $clearFields[] = $field;
            }
        }

        $envelope = $this->bus->dispatch(new UpdateCategoryCommand(
            categoryId: (string) $data->getId(),
            name: $data->getName(),
            obligation: $data->getObligation()->value,
            parentId: $data->getParent() !== null ? (string) $data->getParent()->getId() : null,
            color: $data->getColor(),
            icon: $data->getIcon(),
            clearFields: $clearFields,
        ));

        return $envelope->last(HandledStamp::class)->getResult();
    }
}
