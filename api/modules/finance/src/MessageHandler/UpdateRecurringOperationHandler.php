<?php

declare(strict_types=1);

namespace Maggie\Finance\MessageHandler;

use Doctrine\ORM\EntityManagerInterface;
use Maggie\Finance\Entity\RecurringOperation;
use Maggie\Finance\Enum\DayRule;
use Maggie\Finance\Enum\RecurrencePeriod;
use Maggie\Finance\Enum\ReferenceAmountSource;
use Maggie\Finance\Message\UpdateRecurringOperationCommand;
use Maggie\Finance\Repository\RecurringOperationRepository;
use Maggie\Finance\Service\OwnedReferenceResolver;
use Maggie\Finance\Service\RecurringOperationGuard;
use Maggie\Finance\UseCase\UpdateRecurringOperation;
use Symfony\Component\Messenger\Attribute\AsMessageHandler;

#[AsMessageHandler]
class UpdateRecurringOperationHandler
{
    public function __construct(
        private readonly UpdateRecurringOperation $updateRecurringOperation,
        private readonly RecurringOperationRepository $operationRepository,
        private readonly OwnedReferenceResolver $references,
        private readonly RecurringOperationGuard $guard,
        private readonly EntityManagerInterface $em,
    ) {
    }

    public function __invoke(UpdateRecurringOperationCommand $command): RecurringOperation
    {
        $operation = $this->operationRepository->findOneBy(['id' => $command->recurringOperationId, 'user' => $command->userId])
            ?? throw new \DomainException("Recurring operation not found: {$command->recurringOperationId}");

        $user = $operation->getUser();

        if (null !== $command->categoryId) {
            $operation->setCategory($this->references->category($command->categoryId, $user));
        }
        if (null !== $command->accountId) {
            $operation->setAccount($this->references->account($command->accountId, $user));
        }
        if (null !== $command->label) {
            $operation->setLabel($command->label);
        }
        if (null !== $command->counterpartyName) {
            $operation->setCounterpartyName($command->counterpartyName);
        } elseif ($command->clears('counterpartyName')) {
            $operation->setCounterpartyName(null);
        }
        if (null !== $command->labelPattern) {
            $operation->setLabelPattern($command->labelPattern);
        } elseif ($command->clears('labelPattern')) {
            $operation->setLabelPattern(null);
        }
        if (null !== $command->period) {
            $operation->setPeriod(RecurrencePeriod::from($command->period));
        }
        if (null !== $command->anchorOn) {
            $operation->setAnchorOn(RecurringOperationGuard::date($command->anchorOn, 'anchorOn'));
        }
        if (null !== $command->dayRule) {
            $operation->setDayRule(DayRule::from($command->dayRule));
        }
        if (null !== $command->referenceAmountCents) {
            $operation->setReferenceAmountCents($command->referenceAmountCents);
        }
        if (null !== $command->referenceSource) {
            $operation->setReferenceSource(ReferenceAmountSource::from($command->referenceSource));
        }
        if (null !== $command->amountTolerancePercent) {
            $operation->setAmountTolerancePercent($command->amountTolerancePercent);
        }
        if (null !== $command->dateToleranceDays) {
            $operation->setDateToleranceDays($command->dateToleranceDays);
        }
        if (null !== $command->endsOn) {
            $operation->setEndsOn(RecurringOperationGuard::date($command->endsOn, 'endsOn'));
        } elseif ($command->clears('endsOn')) {
            $operation->setEndsOn(null);
        }

        try {
            $this->guard->assertValid($operation);
        } catch (\DomainException $e) {
            // A refused change must not reach the next flush of this manager.
            $this->em->refresh($operation);

            throw $e;
        }

        return $this->updateRecurringOperation->execute($operation);
    }
}
