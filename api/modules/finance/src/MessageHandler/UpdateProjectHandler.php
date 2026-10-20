<?php

declare(strict_types=1);

namespace Maggie\Finance\MessageHandler;

use Maggie\Finance\Entity\Project;
use Maggie\Finance\Message\UpdateProjectCommand;
use Maggie\Finance\Repository\ProjectRepository;
use Maggie\Finance\Service\ProjectRules;
use Maggie\Finance\UseCase\UpdateProject;
use Symfony\Component\Messenger\Attribute\AsMessageHandler;

/**
 * Everything is checked before anything is written: a refused update leaves
 * the project as it was.
 */
#[AsMessageHandler]
class UpdateProjectHandler
{
    public function __construct(
        private readonly UpdateProject $updateProject,
        private readonly ProjectRepository $projectRepository,
        private readonly ProjectRules $rules,
    ) {
    }

    public function __invoke(UpdateProjectCommand $command): Project
    {
        $project = $this->projectRepository->findOneOfUser($command->userId, $command->projectId)
            ?? throw new \DomainException("Project not found: {$command->projectId}");

        if (null !== $command->name) {
            $this->rules->assertName($command->name);
        }
        if (null !== $command->amountCents) {
            $this->rules->assertAmount($command->amountCents);
        }
        if (null !== $command->currency) {
            $this->rules->assertCurrency($command->currency);
        }
        $wellbeing = null === $command->wellbeing ? null : $this->rules->wellbeing($command->wellbeing);

        $dateMode = null === $command->dateMode ? $project->getDateMode() : $this->rules->dateMode($command->dateMode);
        $targetOn = match (true) {
            null !== $command->targetOn => Project::dayFromString($command->targetOn),
            $command->clears('targetOn') => null,
            default => $project->getTargetOn(),
        };
        $this->rules->assertDate($dateMode, $targetOn);

        $dependencies = match (true) {
            null !== $command->dependsOn => $this->rules->dependencies($command->userId, $project, $command->dependsOn),
            $command->clears('dependsOn') => [],
            default => null,
        };

        if (null !== $command->name) {
            $project->setName($command->name);
        }
        if (null !== $command->amountCents) {
            $project->setAmountCents($command->amountCents);
        }
        if (null !== $command->currency) {
            $project->setCurrency($command->currency);
        }
        if (null !== $wellbeing) {
            $project->setWellbeing($wellbeing);
        }
        $project->setDateMode($dateMode);
        $project->setTargetOn($targetOn);
        if (null !== $dependencies) {
            $project->setDependsOn($dependencies);
        }
        if (null !== $command->note) {
            $project->setNote('' === $command->note ? null : $command->note);
        } elseif ($command->clears('note')) {
            $project->setNote(null);
        }

        return $this->updateProject->execute($project);
    }
}
