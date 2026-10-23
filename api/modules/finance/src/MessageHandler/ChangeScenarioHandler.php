<?php

declare(strict_types=1);

namespace Maggie\Finance\MessageHandler;

use Maggie\Core\Repository\UserRepository;
use Maggie\Finance\Enum\ScenarioKind;
use Maggie\Finance\Enum\ScenarioStatus;
use Maggie\Finance\Message\ChangeScenarioCommand;
use Maggie\Finance\UseCase\ManageScenarios;
use Symfony\Component\Messenger\Attribute\AsMessageHandler;

#[AsMessageHandler]
class ChangeScenarioHandler
{
    public function __construct(
        private readonly ManageScenarios $manageScenarios,
        private readonly UserRepository $userRepository,
    ) {
    }

    /** @return string|null the id of the scenario written, null when the action leaves none */
    public function __invoke(ChangeScenarioCommand $command): ?string
    {
        $user = $this->userRepository->find($command->userId)
            ?? throw new \DomainException('User not found.');
        $id = $command->scenarioId ?? '';

        $scenario = match ($command->action) {
            ChangeScenarioCommand::CREATE => $this->manageScenarios->create($user, $command->fields, ScenarioKind::User, ScenarioStatus::Saved),
            ChangeScenarioCommand::SUGGEST => $this->manageScenarios->create($user, $command->fields, ScenarioKind::Advisor, ScenarioStatus::Draft),
            ChangeScenarioCommand::UPDATE => $this->manageScenarios->update($user, $id, $command->fields),
            ChangeScenarioCommand::DUPLICATE => $this->manageScenarios->duplicate($user, $id, $command->fields['name'] ?? null),
            ChangeScenarioCommand::MAKE_CURRENT => $this->manageScenarios->makeCurrent($user, $id),
            ChangeScenarioCommand::DELETE => $this->manageScenarios->delete($user, $id),
            ChangeScenarioCommand::CLEAR_CURRENT => $this->manageScenarios->clearCurrent($user),
            default => throw new \DomainException("Unknown scenario action: {$command->action}."),
        };

        return null === $scenario ? null : (string) $scenario->getId();
    }
}
