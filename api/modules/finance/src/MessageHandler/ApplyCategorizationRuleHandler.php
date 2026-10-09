<?php

declare(strict_types=1);

namespace Maggie\Finance\MessageHandler;

use Maggie\Finance\Message\ApplyCategorizationRuleCommand;
use Maggie\Finance\Repository\CategorizationRuleRepository;
use Maggie\Finance\UseCase\ApplyCategorizationRule;
use Symfony\Component\Messenger\Attribute\AsMessageHandler;

#[AsMessageHandler]
class ApplyCategorizationRuleHandler
{
    public function __construct(
        private readonly CategorizationRuleRepository $ruleRepository,
        private readonly ApplyCategorizationRule $applyCategorizationRule,
    ) {
    }

    /** @return array{categorized: int, scanned: int} */
    public function __invoke(ApplyCategorizationRuleCommand $command): array
    {
        $rule = $this->ruleRepository->find($command->ruleId)
            ?? throw new \DomainException("Categorization rule not found: {$command->ruleId}");

        return $this->applyCategorizationRule->execute($rule);
    }
}
