<?php

declare(strict_types=1);

namespace Maggie\Finance\State;

use ApiPlatform\Metadata\Operation;
use ApiPlatform\State\ProcessorInterface;
use Maggie\Finance\Entity\Category;
use Maggie\Finance\Message\UpdateCategoryCommand;
use Symfony\Component\Messenger\MessageBusInterface;

/** @implements ProcessorInterface<Category, Category> */
class UpdateCategoryProcessor implements ProcessorInterface
{
    use DispatchesCategoryCommandTrait;

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
            if (null === $value) {
                $clearFields[] = $field;
            }
        }

        return $this->dispatchForCategory($this->bus, new UpdateCategoryCommand(
            userId: (string) $data->getUser()->getId(),
            categoryId: (string) $data->getId(),
            name: $data->getName(),
            obligation: $data->getObligation()->value,
            passiveIncome: $data->isPassiveIncome(),
            parentId: null !== $data->getParent() ? (string) $data->getParent()->getId() : null,
            color: $data->getColor(),
            icon: $data->getIcon(),
            clearFields: $clearFields,
        ));
    }
}
