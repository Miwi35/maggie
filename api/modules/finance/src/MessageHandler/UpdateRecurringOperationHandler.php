<?php

declare(strict_types=1);

namespace Maggie\Finance\MessageHandler;

use Doctrine\ORM\EntityManagerInterface;
use Maggie\Finance\Entity\Category;
use Maggie\Finance\Entity\Qualification;
use Maggie\Finance\Entity\RecurringOperation;
use Maggie\Finance\Enum\DayRule;
use Maggie\Finance\Enum\RecurrencePeriod;
use Maggie\Finance\Enum\ReferenceAmountSource;
use Maggie\Finance\Event\OperationQualificationSaved;
use Maggie\Finance\Message\UpdateRecurringOperationCommand;
use Maggie\Finance\Repository\RecurringOperationRepository;
use Maggie\Finance\Service\OwnedReferenceResolver;
use Maggie\Finance\Service\RecurringOperationGuard;
use Maggie\Finance\UseCase\UpdateRecurringOperation;
use Symfony\Component\DependencyInjection\Attribute\Autowire;
use Symfony\Component\Messenger\Attribute\AsMessageHandler;
use Symfony\Component\Messenger\MessageBusInterface;

#[AsMessageHandler]
class UpdateRecurringOperationHandler
{
    public function __construct(
        private readonly UpdateRecurringOperation $updateRecurringOperation,
        private readonly RecurringOperationRepository $operationRepository,
        private readonly OwnedReferenceResolver $references,
        private readonly RecurringOperationGuard $guard,
        private readonly EntityManagerInterface $em,
        #[Autowire(service: 'event.bus')]
        private readonly MessageBusInterface $eventBus,
    ) {
    }

    public function __invoke(UpdateRecurringOperationCommand $command): RecurringOperation
    {
        $operation = $this->operationRepository->findOneBy(['id' => $command->recurringOperationId, 'user' => $command->userId])
            ?? throw new \DomainException("Recurring operation not found: {$command->recurringOperationId}");

        // As loaded, not as held: over REST the deserializer has already
        // written the new category on this very object.
        $formerCategory = $this->em->getUnitOfWork()->getOriginalEntityData($operation)['category'] ?? $operation->getCategory();

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

            $this->guard->assertValid($operation);
        } catch (\DomainException|\ValueError $e) {
            // A refused change must not reach the next flush of this manager.
            $this->em->refresh($operation);

            throw $e;
        }

        $operation = $this->updateRecurringOperation->execute($operation);
        $this->eventBus->dispatch(new OperationQualificationSaved(RecurringOperation::class, (string) $operation->getId()));

        return $operation;
    }
}
