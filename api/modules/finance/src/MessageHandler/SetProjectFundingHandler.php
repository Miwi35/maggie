<?php

declare(strict_types=1);

namespace Maggie\Finance\MessageHandler;

use Maggie\Core\Repository\UserRepository;
use Maggie\Finance\Entity\Project;
use Maggie\Finance\Message\SetProjectFundingCommand;
use Maggie\Finance\Repository\ProjectRepository;
use Maggie\Finance\Service\ProjectFundingRules;
use Maggie\Finance\UseCase\SetProjectFunding;
use Symfony\Component\Messenger\Attribute\AsMessageHandler;

/**
 * Every source is checked before anything is written: a refused list leaves
 * the project and its projected loan as they were.
 */
#[AsMessageHandler]
class SetProjectFundingHandler
{
    public function __construct(
        private readonly SetProjectFunding $setProjectFunding,
        private readonly ProjectRepository $projectRepository,
        private readonly UserRepository $userRepository,
        private readonly ProjectFundingRules $rules,
    ) {
    }

    public function __invoke(SetProjectFundingCommand $command): Project
    {
        $user = $this->userRepository->find($command->userId)
            ?? throw new \DomainException('User not found.');
        $project = $this->projectRepository->findOneOfUser($user, $command->projectId)
            ?? throw new \DomainException("Project not found: {$command->projectId}");

        return $this->setProjectFunding->execute($project, $this->rules->sources($project, $user, $command->sources));
    }
}
