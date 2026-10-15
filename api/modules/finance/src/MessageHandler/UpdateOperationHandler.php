<?php

declare(strict_types=1);

namespace Maggie\Finance\MessageHandler;

use Doctrine\ORM\EntityManagerInterface;
use Maggie\Finance\Entity\Category;
use Maggie\Finance\Entity\Qualification;
use Maggie\Finance\Entity\RecurringOperation;
use Maggie\Finance\Enum\AmountDirection;
use Maggie\Finance\Enum\DayRule;
use Maggie\Finance\Enum\MatchType;
use Maggie\Finance\Enum\RecurrencePeriod;
use Maggie\Finance\Enum\ReferenceAmountSource;
use Maggie\Finance\Event\OperationQualificationSaved;
use Maggie\Finance\Event\OperationSaved;
use Maggie\Finance\Message\UpdateOperationCommand;
use Maggie\Finance\Repository\RecurringOperationRepository;
use Maggie\Finance\Service\OperationGuard;
use Maggie\Finance\Service\OwnedReferenceResolver;
use Maggie\Finance\UseCase\UpdateOperation;
use Symfony\Component\DependencyInjection\Attribute\Autowire;
use Symfony\Component\Messenger\Attribute\AsMessageHandler;
use Symfony\Component\Messenger\MessageBusInterface;

#[AsMessageHandler]
class UpdateOperationHandler
{
    public const string RECURRENCE_KEPT = 'An operation keeps the recurrence it was created with, or its lack of one: create a new operation instead.';

    public function __construct(
        private readonly UpdateOperation $updateOperation,
        private readonly RecurringOperationRepository $operationRepository,
        private readonly OwnedReferenceResolver $references,
        private readonly OperationGuard $guard,
        private readonly EntityManagerInterface $em,
        #[Autowire(service: 'event.bus')]
        private readonly MessageBusInterface $eventBus,
    ) {
    }

    public function __invoke(UpdateOperationCommand $command): RecurringOperation
    {
        $operation = $this->operationRepository->findOneBy(['id' => $command->operationId, 'user' => $command->userId])
            ?? throw new \DomainException("Operation not found: {$command->operationId}");

        // As loaded, not as held: over REST the deserializer has already
        // written the new values on this very object.
        $original = $this->em->getUnitOfWork()->getOriginalEntityData($operation);
        $formerCategory = $original['category'] ?? $operation->getCategory();
        $wasRecurring = \array_key_exists('period', $original) ? null !== $original['period'] : $operation->hasRecurrence();

        try {
            $user = $operation->getUser();
            $category = null !== $command->categoryId
                ? $this->references->category($command->categoryId, $user)
                : $operation->getCategory();
            $categoryChanged = !$formerCategory instanceof Category || !$formerCategory->getId()->equals($category->getId());

            if (null !== $command->qualification) {
                $operation->requalify(Qualification::fromArray($command->qualification, $category));
            } elseif ($categoryChanged) {
                // Until MAG-479 has the clients send a qualification, a new
                // category carries the one its deprecated flag meant.
                $operation->requalify(Qualification::fromCategory($category));
            }

            $this->applyRecognition($operation, $command);
            $this->applyRecurrence($operation, $command);

            if ($wasRecurring !== $operation->hasRecurrence()) {
                throw new \DomainException(self::RECURRENCE_KEPT);
            }

            $this->guard->assertValid($operation);
        } catch (\DomainException|\ValueError $e) {
            // A refused change must not reach the next flush of this manager.
            $this->em->refresh($operation);
            throw $e;
        }

        $operation = $this->updateOperation->execute($operation);
        $this->eventBus->dispatch(new OperationQualificationSaved((string) $operation->getId()));
        $this->eventBus->dispatch(new OperationSaved((string) $operation->getId(), $command->applyToExisting));

        return $operation;
    }

    private function applyRecognition(RecurringOperation $operation, UpdateOperationCommand $command): void
    {
        if (null !== $command->label) {
            $operation->setLabel($command->label);
        } elseif ($command->clears('label')) {
            $operation->setLabel(null);
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

        if (null !== $command->matchType) {
            $operation->setMatchType(MatchType::from($command->matchType));
        }

        if (null !== $command->direction) {
            $operation->setDirection(AmountDirection::from($command->direction));
        }

        if (null !== $command->minAmountCents) {
            $operation->setMinAmountCents($command->minAmountCents);
        } elseif ($command->clears('minAmountCents')) {
            $operation->setMinAmountCents(null);
        }

        if (null !== $command->maxAmountCents) {
            $operation->setMaxAmountCents($command->maxAmountCents);
        } elseif ($command->clears('maxAmountCents')) {
            $operation->setMaxAmountCents(null);
        }

        if (null !== $command->priority) {
            $operation->setPriority($command->priority);
        }

        if (null !== $command->isActive) {
            $operation->setIsActive($command->isActive);
        }
    }

    private function applyRecurrence(RecurringOperation $operation, UpdateOperationCommand $command): void
    {
        if (null !== $command->period) {
            $operation->setPeriod(RecurrencePeriod::from($command->period));
        }

        if (null !== $command->anchorOn) {
            $operation->setAnchorOn(OperationGuard::date($command->anchorOn, 'anchorOn'));
        }

        if (null !== $command->referenceAmountCents) {
            $operation->setReferenceAmountCents($command->referenceAmountCents);
        }

        if (null !== $command->accountId) {
            $operation->setAccount($this->references->account($command->accountId, $operation->getUser()));
        }

        if (null !== $command->dayRule) {
            $operation->setDayRule(DayRule::from($command->dayRule));
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
            $operation->setEndsOn(OperationGuard::date($command->endsOn, 'endsOn'));
        } elseif ($command->clears('endsOn')) {
            $operation->setEndsOn(null);
        }
    }
}
