<?php

declare(strict_types=1);

namespace Maggie\Finance\MessageHandler;

use Maggie\Core\Repository\UserRepository;
use Maggie\Finance\Entity\Project;
use Maggie\Finance\Message\SetProjectInvestmentCommand;
use Maggie\Finance\Repository\ProjectRepository;
use Maggie\Finance\Service\ProjectInvestmentRules;
use Maggie\Finance\UseCase\SetProjectInvestment;
use Symfony\Component\Messenger\Attribute\AsMessageHandler;

/** Everything is checked before anything is written: a refused investment leaves the project as it was. */
#[AsMessageHandler]
class SetProjectInvestmentHandler
{
    public function __construct(
        private readonly SetProjectInvestment $setProjectInvestment,
        private readonly ProjectRepository $projectRepository,
        private readonly UserRepository $userRepository,
        private readonly ProjectInvestmentRules $rules,
    ) {
    }

    public function __invoke(SetProjectInvestmentCommand $command): Project
    {
        $user = $this->userRepository->find($command->userId)
            ?? throw new \DomainException('User not found.');
        $project = $this->projectRepository->findOneOfUser($user, $command->projectId)
            ?? throw new \DomainException("Project not found: {$command->projectId}");

        $checked = $this->rules->check($project, $user, $command->data);

        return $this->setProjectInvestment->execute($project, $checked['priceCents'], $checked['sources'], $checked['rentCents'], $checked['resale']);
    }
}
