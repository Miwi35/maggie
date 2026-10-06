<?php

declare(strict_types=1);

namespace Maggie\Finance\MessageHandler;

use Maggie\Finance\Entity\CategorizationRule;
use Maggie\Finance\Enum\AmountDirection;
use Maggie\Finance\Enum\MatchType;
use Maggie\Finance\Message\UpdateCategorizationRuleCommand;
use Maggie\Finance\Repository\CategorizationRuleRepository;
use Maggie\Finance\Service\OwnedReferenceResolver;
use Maggie\Finance\UseCase\UpdateCategorizationRule;
use Symfony\Component\Messenger\Attribute\AsMessageHandler;

#[AsMessageHandler]
class UpdateCategorizationRuleHandler
{
    public function __construct(
        private readonly UpdateCategorizationRule $updateCategorizationRule,
        private readonly CategorizationRuleRepository $ruleRepository,
        private readonly OwnedReferenceResolver $references,
    ) {
    }

    public function __invoke(UpdateCategorizationRuleCommand $command): CategorizationRule
    {
        $rule = $this->ruleRepository->findOneBy(['id' => $command->categorizationRuleId, 'user' => $command->userId])
            ?? throw new \DomainException("Categorization rule not found: {$command->categorizationRuleId}");

        if (null !== $command->categoryId) {
            $category = $this->references->category($command->categoryId, $rule->getUser());
            $rule->setCategory($category);
        }
        if (null !== $command->labelPattern) {
            if ('' === $command->labelPattern) {
                throw new \DomainException('A rule needs a label pattern to match on.');
            }
            $rule->setLabelPattern($command->labelPattern);
        }
        if (null !== $command->matchType) {
            $rule->setMatchType(MatchType::from($command->matchType));
        }
        if (null !== $command->direction) {
            $rule->setDirection(AmountDirection::from($command->direction));
        }
        if (null !== $command->minAmountCents) {
            $rule->setMinAmountCents($command->minAmountCents);
        } elseif ($command->clears('minAmountCents')) {
            $rule->setMinAmountCents(null);
        }
        if (null !== $command->maxAmountCents) {
            $rule->setMaxAmountCents($command->maxAmountCents);
        } elseif ($command->clears('maxAmountCents')) {
            $rule->setMaxAmountCents(null);
        }
        if (null !== $command->priority) {
            $rule->setPriority($command->priority);
        }
        if (null !== $command->isActive) {
            $rule->setIsActive($command->isActive);
        }

        $min = $rule->getMinAmountCents();
        $max = $rule->getMaxAmountCents();
        if (null !== $min && null !== $max && $min > $max) {
            throw new \DomainException('The minimum amount must not exceed the maximum amount.');
        }

        return $this->updateCategorizationRule->execute($rule);
    }
}
