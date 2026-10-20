<?php

declare(strict_types=1);

namespace Maggie\Finance\State;

use ApiPlatform\Metadata\Operation;
use ApiPlatform\State\ProcessorInterface;
use Maggie\Finance\Entity\Project;
use Maggie\Finance\Message\DeleteProjectCommand;
use Symfony\Component\Messenger\MessageBusInterface;

/** @implements ProcessorInterface<Project, void> */
class DeleteProjectProcessor implements ProcessorInterface
{
    use DispatchesProjectCommandTrait;

    public function __construct(
        private readonly MessageBusInterface $bus,
    ) {
    }

    public function process(mixed $data, Operation $operation, array $uriVariables = [], array $context = []): void
    {
        $this->dispatchProjectCommand($this->bus, new DeleteProjectCommand(
            userId: (string) $data->getUser()->getId(),
            projectId: (string) $data->getId(),
        ));
    }
}
