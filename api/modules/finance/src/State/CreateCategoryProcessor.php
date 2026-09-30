<?php

declare(strict_types=1);

namespace Maggie\Finance\State;

use ApiPlatform\Metadata\Operation;
use ApiPlatform\State\ProcessorInterface;
use Maggie\Core\Entity\User;
use Maggie\Finance\Entity\Category;
use Maggie\Finance\Message\CreateCategoryCommand;
use Symfony\Bundle\SecurityBundle\Security;
use Symfony\Component\Messenger\MessageBusInterface;
use Symfony\Component\Messenger\Stamp\HandledStamp;

/** @implements ProcessorInterface<Category, Category> */
class CreateCategoryProcessor implements ProcessorInterface
{
    public function __construct(
        private readonly MessageBusInterface $bus,
        private readonly Security $security,
    ) {
    }

    public function process(mixed $data, Operation $operation, array $uriVariables = [], array $context = []): Category
    {
        /** @var User $user */
        $user = $this->security->getUser();

        $envelope = $this->bus->dispatch(new CreateCategoryCommand(
            userId: (string) $user->getId(),
            name: $data->getName(),
            obligation: $data->getObligation()->value,
            parentId: null !== $data->getParent() ? (string) $data->getParent()->getId() : null,
            color: $data->getColor(),
            icon: $data->getIcon(),
        ));

        return $envelope->last(HandledStamp::class)->getResult();
    }
}
