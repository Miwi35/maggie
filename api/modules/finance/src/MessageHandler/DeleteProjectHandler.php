<?php

declare(strict_types=1);

namespace Maggie\Finance\MessageHandler;

use Maggie\Finance\Message\DeleteProjectCommand;
use Maggie\Finance\Repository\ProjectRepository;
use Maggie\Finance\UseCase\DeleteProject;
use Symfony\Component\Messenger\Attribute\AsMessageHandler;

#[AsMessageHandler]
class DeleteProjectHandler
{
    public function __construct(
        private readonly DeleteProject $deleteProject,
        private readonly ProjectRepository $projectRepository,
    ) {
    }

    public function __invoke(DeleteProjectCommand $command): void
    {
        $project = $this->projectRepository->findOneOfUser($command->userId, $command->projectId)
            ?? throw new \DomainException("Project not found: {$command->projectId}");

        $this->deleteProject->execute($project);
    }
}
