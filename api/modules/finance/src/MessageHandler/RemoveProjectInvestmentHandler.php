<?php

declare(strict_types=1);

namespace Maggie\Finance\MessageHandler;

use Maggie\Finance\Entity\Project;
use Maggie\Finance\Message\RemoveProjectInvestmentCommand;
use Maggie\Finance\Repository\ProjectRepository;
use Maggie\Finance\UseCase\RemoveProjectInvestment;
use Symfony\Component\Messenger\Attribute\AsMessageHandler;

#[AsMessageHandler]
class RemoveProjectInvestmentHandler
{
    public function __construct(
        private readonly RemoveProjectInvestment $removeProjectInvestment,
        private readonly ProjectRepository $projectRepository,
    ) {
    }

    public function __invoke(RemoveProjectInvestmentCommand $command): Project
    {
        $project = $this->projectRepository->findOneOfUser($command->userId, $command->projectId)
            ?? throw new \DomainException("Project not found: {$command->projectId}");
        if (null === $project->getInvestment()) {
            throw new \DomainException('This project is not an investment.');
        }

        return $this->removeProjectInvestment->execute($project);
    }
}
