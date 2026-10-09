<?php

declare(strict_types=1);

namespace Maggie\Finance\MessageHandler;

use Maggie\Core\Repository\UserRepository;
use Maggie\Finance\Entity\RecurringOperation;
use Maggie\Finance\Enum\DayRule;
use Maggie\Finance\Enum\RecurrencePeriod;
use Maggie\Finance\Enum\ReferenceAmountSource;
use Maggie\Finance\Message\CreateRecurringOperationCommand;
use Maggie\Finance\Service\OwnedReferenceResolver;
use Maggie\Finance\Service\RecurringOperationGuard;
use Maggie\Finance\UseCase\CreateRecurringOperation;
use Symfony\Component\Messenger\Attribute\AsMessageHandler;

#[AsMessageHandler]
class CreateRecurringOperationHandler
{
    public function __construct(
        private readonly CreateRecurringOperation $createRecurringOperation,
        private readonly OwnedReferenceResolver $references,
        private readonly UserRepository $userRepository,
        private readonly RecurringOperationGuard $guard,
    ) {
    }

    public function __invoke(CreateRecurringOperationCommand $command): RecurringOperation
    {
        $user = $this->userRepository->find($command->userId)
            ?? throw new \DomainException('User not found.');

        $operation = new RecurringOperation();
        $operation->setUser($user);
        $operation->setCategory($this->references->category($command->categoryId, $user));
        $operation->setAccount($this->references->account($command->accountId, $user));
        $operation->setLabel($command->label);
        $operation->setCounterpartyName($command->counterpartyName);
        $operation->setLabelPattern($command->labelPattern);
        $operation->setPeriod(RecurrencePeriod::from($command->period));
        $operation->setAnchorOn(RecurringOperationGuard::date($command->anchorOn, 'anchorOn'));
        $operation->setDayRule(DayRule::from($command->dayRule));
        $operation->setReferenceAmountCents($command->referenceAmountCents);
        $operation->setReferenceSource(ReferenceAmountSource::from($command->referenceSource));
        $operation->setAmountTolerancePercent($command->amountTolerancePercent);
        $operation->setDateToleranceDays($command->dateToleranceDays);
        $operation->setEndsOn(null === $command->endsOn ? null : RecurringOperationGuard::date($command->endsOn, 'endsOn'));

        $this->guard->assertValid($operation);

        return $this->createRecurringOperation->execute($operation);
    }
}
