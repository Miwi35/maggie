<?php

declare(strict_types=1);

namespace Maggie\Finance\State;

use ApiPlatform\Metadata\Operation;
use ApiPlatform\State\ProcessorInterface;
use Maggie\Core\Entity\User;
use Maggie\Finance\Entity\CategorizationRule;
use Maggie\Finance\Message\CreateCategorizationRuleCommand;
use Symfony\Bundle\SecurityBundle\Security;
use Symfony\Component\Messenger\MessageBusInterface;
use Symfony\Component\Messenger\Stamp\HandledStamp;

/** @implements ProcessorInterface<CategorizationRule, CategorizationRule> */
class CreateCategorizationRuleProcessor implements ProcessorInterface
{
    public function __construct(
        private readonly MessageBusInterface $bus,
        private readonly Security $security,
    ) {
    }

    public function process(mixed $data, Operation $operation, array $uriVariables = [], array $context = []): CategorizationRule
    {
        /** @var User $user */
        $user = $this->security->getUser();

        $stamped = $this->bus->dispatch(new CreateCategorizationRuleCommand(
            userId: (string) $user->getId(),
            labelPattern: $data->getLabelPattern(),
            categoryId: (string) $data->getCategory()->getId(),
            matchType: $data->getMatchType()->value,
            direction: $data->getDirection()->value,
            minAmountCents: $data->getMinAmountCents(),
            maxAmountCents: $data->getMaxAmountCents(),
            priority: $data->getPriority(),
            isActive: $data->isActive(),
        ));

        return $stamped->last(HandledStamp::class)->getResult();
    }
}
