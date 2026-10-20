<?php

declare(strict_types=1);

namespace Maggie\Finance\MessageHandler;

use Maggie\Core\Repository\UserRepository;
use Maggie\Finance\Entity\Project;
use Maggie\Finance\Message\CreateProjectCommand;
use Maggie\Finance\Service\ProjectRules;
use Maggie\Finance\UseCase\CreateProject;
use Symfony\Component\Messenger\Attribute\AsMessageHandler;

#[AsMessageHandler]
class CreateProjectHandler
{
    public function __construct(
        private readonly CreateProject $createProject,
        private readonly UserRepository $userRepository,
        private readonly ProjectRules $rules,
    ) {
    }

    public function __invoke(CreateProjectCommand $command): Project
    {
        $user = $this->userRepository->find($command->userId)
            ?? throw new \DomainException('User not found.');

        $this->rules->assertName($command->name);
        $this->rules->assertAmount($command->amountCents);
        $this->rules->assertCurrency($command->currency);
        $wellbeing = $this->rules->wellbeing($command->wellbeing);
        $dateMode = $this->rules->dateMode($command->dateMode);
        $targetOn = null === $command->targetOn ? null : Project::dayFromString($command->targetOn);
        $this->rules->assertDate($dateMode, $targetOn);
        $dependencies = $this->rules->dependencies($user, null, $command->dependsOn);

        $project = new Project();
        $project->setUser($user);
        $project->setName($command->name);
        $project->setAmountCents($command->amountCents);
        $project->setCurrency($command->currency);
        $project->setWellbeing($wellbeing);
        $project->setDateMode($dateMode);
        $project->setTargetOn($targetOn);
        $project->setDependsOn($dependencies);
        $project->setNote('' === $command->note ? null : $command->note);

        return $this->createProject->execute($project);
    }
}
