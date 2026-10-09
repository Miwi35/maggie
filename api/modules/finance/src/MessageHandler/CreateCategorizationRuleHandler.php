<?php

declare(strict_types=1);

namespace Maggie\Finance\MessageHandler;

use Maggie\Core\Repository\UserRepository;
use Maggie\Finance\Entity\CategorizationRule;
use Maggie\Finance\Enum\AmountDirection;
use Maggie\Finance\Enum\MatchType;
use Maggie\Finance\Event\CategorizationRuleSaved;
use Maggie\Finance\Message\CreateCategorizationRuleCommand;
use Maggie\Finance\Service\OwnedReferenceResolver;
use Maggie\Finance\UseCase\CreateCategorizationRule;
use Symfony\Component\DependencyInjection\Attribute\Autowire;
use Symfony\Component\Messenger\Attribute\AsMessageHandler;
use Symfony\Component\Messenger\MessageBusInterface;

#[AsMessageHandler]
class CreateCategorizationRuleHandler
{
    public function __construct(
        private readonly CreateCategorizationRule $createCategorizationRule,
        private readonly OwnedReferenceResolver $references,
        private readonly UserRepository $userRepository,
        #[Autowire(service: 'event.bus')]
        private readonly MessageBusInterface $eventBus,
    ) {
    }

    public function __invoke(CreateCategorizationRuleCommand $command): CategorizationRule
    {
        $user = $this->userRepository->find($command->userId)
            ?? throw new \DomainException('User not found.');

        $category = $this->references->category($command->categoryId, $user);

        if ('' === $command->labelPattern) {
            throw new \DomainException('A rule needs a label pattern to match on.');
        }

        if (null !== $command->minAmountCents
            && null !== $command->maxAmountCents
            && $command->minAmountCents > $command->maxAmountCents
        ) {
            throw new \DomainException('The minimum amount must not exceed the maximum amount.');
        }

        $rule = new CategorizationRule();
        $rule->setUser($user);
        $rule->setCategory($category);
        $rule->setLabelPattern($command->labelPattern);
        $rule->setMatchType(MatchType::from($command->matchType));
        $rule->setDirection(AmountDirection::from($command->direction));
        $rule->setMinAmountCents($command->minAmountCents);
        $rule->setMaxAmountCents($command->maxAmountCents);
        $rule->setPriority($command->priority);
        $rule->setIsActive($command->isActive);

        $rule = $this->createCategorizationRule->execute($rule);
        $this->eventBus->dispatch(new CategorizationRuleSaved((string) $rule->getId(), $command->applyToExisting));

        return $rule;
    }
}
