<?php

declare(strict_types=1);

namespace Maggie\Finance\MessageHandler;

use Maggie\Finance\Message\DeleteCategorizationRuleCommand;
use Maggie\Finance\Repository\CategorizationRuleRepository;
use Maggie\Finance\UseCase\DeleteCategorizationRule;
use Symfony\Component\Messenger\Attribute\AsMessageHandler;

#[AsMessageHandler]
class DeleteCategorizationRuleHandler
{
    public function __construct(
        private readonly DeleteCategorizationRule $deleteCategorizationRule,
        private readonly CategorizationRuleRepository $ruleRepository,
    ) {
    }

    public function __invoke(DeleteCategorizationRuleCommand $command): void
    {
        $rule = $this->ruleRepository->findOneBy(['id' => $command->categorizationRuleId, 'user' => $command->userId])
            ?? throw new \DomainException("Categorization rule not found: {$command->categorizationRuleId}");

        $this->deleteCategorizationRule->execute($rule);
    }
}
