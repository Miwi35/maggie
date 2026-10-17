<?php

declare(strict_types=1);

namespace Maggie\Finance\MessageHandler;

use Maggie\Core\Repository\UserRepository;
use Maggie\Finance\Entity\Qualification;
use Maggie\Finance\Entity\RecurringOperation;
use Maggie\Finance\Enum\AmountDirection;
use Maggie\Finance\Enum\DayRule;
use Maggie\Finance\Enum\MatchType;
use Maggie\Finance\Enum\RecurrencePeriod;
use Maggie\Finance\Enum\ReferenceAmountSource;
use Maggie\Finance\Event\OperationSaved;
use Maggie\Finance\Message\CreateOperationCommand;
use Maggie\Finance\Service\OperationGuard;
use Maggie\Finance\Service\OwnedReferenceResolver;
use Maggie\Finance\UseCase\CreateOperation;
use Symfony\Component\DependencyInjection\Attribute\Autowire;
use Symfony\Component\Messenger\Attribute\AsMessageHandler;
use Symfony\Component\Messenger\MessageBusInterface;

#[AsMessageHandler]
class CreateOperationHandler
{
    public function __construct(
        private readonly CreateOperation $createOperation,
        private readonly OwnedReferenceResolver $references,
        private readonly UserRepository $userRepository,
        private readonly OperationGuard $guard,
        #[Autowire(service: 'event.bus')]
        private readonly MessageBusInterface $eventBus,
    ) {
    }

    public function __invoke(CreateOperationCommand $command): RecurringOperation
    {
        $user = $this->userRepository->find($command->userId)
            ?? throw new \DomainException('User not found.');

        $operation = new RecurringOperation();
        $operation->setUser($user);
        $category = $this->references->category($command->categoryId, $user);
        $operation->requalify(null !== $command->qualification
            ? Qualification::fromArray($command->qualification, $category)
            : Qualification::fromCategory($category));
        $operation->setLabel($command->label);
        $operation->setCounterpartyName($command->counterpartyName);
        $operation->setLabelPattern($command->labelPattern);
        $operation->setMatchType(MatchType::from($command->matchType));
        $operation->setDirection(AmountDirection::from($command->direction));
        $operation->setMinAmountCents($command->minAmountCents);
        $operation->setMaxAmountCents($command->maxAmountCents);
        $operation->setPriority($command->priority);
        $operation->setIsActive($command->isActive);
        $operation->setPeriod(null === $command->period ? null : RecurrencePeriod::from($command->period));
        $operation->setAnchorOn(null === $command->anchorOn ? null : OperationGuard::date($command->anchorOn, 'anchorOn'));
        $operation->setReferenceAmountCents($command->referenceAmountCents);
        $operation->setAccount(null === $command->accountId ? null : $this->references->account($command->accountId, $user));
        $operation->setDayRule(DayRule::from($command->dayRule));
        $operation->setReferenceSource(ReferenceAmountSource::from($command->referenceSource));
        $operation->setAmountTolerancePercent($command->amountTolerancePercent);
        $operation->setDateToleranceDays($command->dateToleranceDays);
        $operation->setEndsOn(null === $command->endsOn ? null : OperationGuard::date($command->endsOn, 'endsOn'));

        $this->guard->assertValid($operation);

        $operation = $this->createOperation->execute($operation);
        $this->eventBus->dispatch(new OperationSaved((string) $operation->getId(), $command->applyToExisting));

        return $operation;
    }
}
