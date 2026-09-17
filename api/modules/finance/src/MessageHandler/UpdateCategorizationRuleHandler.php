<?php

declare(strict_types=1);

namespace Maggie\Finance\MessageHandler;

use Maggie\Finance\Entity\CategorizationRule;
use Maggie\Finance\Enum\AmountDirection;
use Maggie\Finance\Enum\MatchType;
use Maggie\Finance\Message\UpdateCategorizationRuleCommand;
use Maggie\Finance\Repository\CategorizationRuleRepository;
use Maggie\Finance\Repository\CategoryRepository;
use Maggie\Finance\UseCase\UpdateCategorizationRule;
use Symfony\Component\Messenger\Attribute\AsMessageHandler;

#[AsMessageHandler]
class UpdateCategorizationRuleHandler
{
    public function __construct(
        private readonly UpdateCategorizationRule $updateCategorizationRule,
        private readonly CategorizationRuleRepository $ruleRepository,
        private readonly CategoryRepository $categoryRepository,
    ) {
    }

    public function __invoke(UpdateCategorizationRuleCommand $command): CategorizationRule
    {
        $rule = $this->ruleRepository->find($command->categorizationRuleId)
            ?? throw new \DomainException("Categorization rule not found: {$command->categorizationRuleId}");

        if ($command->categoryId !== null) {
            $category = $this->categoryRepository->find($command->categoryId)
                ?? throw new \DomainException("Category not found: {$command->categoryId}");
            $rule->setCategory($category);
        }
        if ($command->labelPattern !== null) {
            if ($command->labelPattern === '') {
                throw new \DomainException('A rule needs a label pattern to match on.');
            }
            $rule->setLabelPattern($command->labelPattern);
        }
        if ($command->matchType !== null) {
            $rule->setMatchType(MatchType::from($command->matchType));
        }
        if ($command->direction !== null) {
            $rule->setDirection(AmountDirection::from($command->direction));
        }
        if ($command->minAmountCents !== null) {
            $rule->setMinAmountCents($command->minAmountCents);
        }
        if ($command->maxAmountCents !== null) {
            $rule->setMaxAmountCents($command->maxAmountCents);
        }
        if ($command->priority !== null) {
            $rule->setPriority($command->priority);
        }
        if ($command->isActive !== null) {
            $rule->setIsActive($command->isActive);
        }

        $min = $rule->getMinAmountCents();
        $max = $rule->getMaxAmountCents();
        if ($min !== null && $max !== null && $min > $max) {
            throw new \DomainException('The minimum amount must not exceed the maximum amount.');
        }

        return $this->updateCategorizationRule->execute($rule);
    }
}
