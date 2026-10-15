<?php

declare(strict_types=1);

namespace Maggie\Finance\MessageHandler;

use Doctrine\ORM\EntityManagerInterface;
use Maggie\Finance\Entity\CategorizationRule;
use Maggie\Finance\Entity\Category;
use Maggie\Finance\Entity\Qualification;
use Maggie\Finance\Enum\AmountDirection;
use Maggie\Finance\Enum\MatchType;
use Maggie\Finance\Event\CategorizationRuleSaved;
use Maggie\Finance\Event\OperationQualificationSaved;
use Maggie\Finance\Message\UpdateCategorizationRuleCommand;
use Maggie\Finance\Repository\CategorizationRuleRepository;
use Maggie\Finance\Service\OwnedReferenceResolver;
use Maggie\Finance\UseCase\UpdateCategorizationRule;
use Symfony\Component\DependencyInjection\Attribute\Autowire;
use Symfony\Component\Messenger\Attribute\AsMessageHandler;
use Symfony\Component\Messenger\MessageBusInterface;

#[AsMessageHandler]
class UpdateCategorizationRuleHandler
{
    public function __construct(
        private readonly UpdateCategorizationRule $updateCategorizationRule,
        private readonly CategorizationRuleRepository $ruleRepository,
        private readonly EntityManagerInterface $em,
        private readonly OwnedReferenceResolver $references,
        private readonly EntityManagerInterface $em,
        #[Autowire(service: 'event.bus')]
        private readonly MessageBusInterface $eventBus,
    ) {
    }

    public function __invoke(UpdateCategorizationRuleCommand $command): CategorizationRule
    {
        $rule = $this->ruleRepository->findOneBy(['id' => $command->categorizationRuleId, 'user' => $command->userId])
            ?? throw new \DomainException("Categorization rule not found: {$command->categorizationRuleId}");

        // As loaded, not as held: over REST the deserializer may already have
        // written the new category on this very object.
        $formerCategory = $this->em->getUnitOfWork()->getOriginalEntityData($rule)['category'] ?? $rule->getCategory();
        $category = null !== $command->categoryId
            ? $this->references->category($command->categoryId, $rule->getUser())
            : $rule->getCategory();
        if (null !== $command->qualification) {
            $rule->requalify(Qualification::fromArray($command->qualification, $category));
        } elseif (!$formerCategory instanceof Category || !$formerCategory->getId()->equals($category->getId())) {
            // Until MAG-479 has the clients send a qualification, a new
            // category carries the one its deprecated flag meant.
            $rule->requalify(Qualification::fromCategory($category));
        }
        if (null !== $command->labelPattern) {
            $rule->setLabelPattern($command->labelPattern);
        } elseif ($command->clears('labelPattern')) {
            $rule->setLabelPattern(null);
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

        if (!$rule->matchCriteria()->isRecognisable()) {
            $this->em->refresh($rule);

            throw new \DomainException(CategorizationRule::UNRECOGNISABLE);
        }

        $rule = $this->updateCategorizationRule->execute($rule);
        $this->eventBus->dispatch(new OperationQualificationSaved(CategorizationRule::class, (string) $rule->getId()));
        $this->eventBus->dispatch(new CategorizationRuleSaved((string) $rule->getId(), $command->applyToExisting));

        return $rule;
    }
}
