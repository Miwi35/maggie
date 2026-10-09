<?php

declare(strict_types=1);

namespace Maggie\Finance\Doctrine;

use Doctrine\Bundle\DoctrineBundle\Attribute\AsDoctrineListener;
use Doctrine\ORM\Event\OnFlushEventArgs;
use Doctrine\ORM\Event\PreFlushEventArgs;
use Doctrine\ORM\Events;
use Maggie\Finance\Entity\Transaction;
use Maggie\Finance\Event\TransactionChanged;
use Maggie\Finance\Event\TransactionRecorded;
use Maggie\Finance\Event\TransactionRemoved;
use Maggie\Finance\Specification\HasDetectionRelevantChange;
use Maggie\Finance\Specification\IsLinkedTransactionRemoved;
use Maggie\Finance\Specification\IsNewTransaction;
use Psr\Log\LoggerInterface;
use Symfony\Component\DependencyInjection\Attribute\Autowire;
use Symfony\Component\Messenger\MessageBusInterface;

/**
 * Raises the events of a transaction's life and nothing else: the effects live in their handlers.
 *
 * The changeset only exists during onFlush; the events go out after the flush,
 * once per transaction, so a sync inserting dozens of lines finds all of them
 * stored when it looks for the other leg of a transfer. The postFlush runs
 * after the projection collector's: a nested flush would otherwise overwrite
 * what that one noted.
 */
#[AsDoctrineListener(event: Events::preFlush)]
#[AsDoctrineListener(event: Events::onFlush)]
#[AsDoctrineListener(event: Events::postFlush, priority: -100)]
final class TransactionListener
{
    /** @var list<array{Transaction, array<string, array{mixed, mixed}>}> */
    private array $saved = [];

    /** @var list<Transaction> */
    private array $removed = [];

    public function __construct(
        #[Autowire(service: 'event.bus')]
        private readonly MessageBusInterface $eventBus,
        private readonly LoggerInterface $logger,
    ) {
    }

    /** A flush that failed after onFlush never reached postFlush: its changes must not outlive it. */
    public function preFlush(PreFlushEventArgs $args): void
    {
        $this->saved = [];
        $this->removed = [];
    }

    public function onFlush(OnFlushEventArgs $args): void
    {
        $uow = $args->getObjectManager()->getUnitOfWork();

        foreach ([...$uow->getScheduledEntityInsertions(), ...$uow->getScheduledEntityUpdates()] as $entity) {
            if ($entity instanceof Transaction) {
                $this->saved[] = [$entity, $uow->getEntityChangeSet($entity)];
            }
        }

        foreach ($uow->getScheduledEntityDeletions() as $entity) {
            if ($entity instanceof Transaction) {
                $this->removed[] = $entity;
            }
        }
    }

    public function postFlush(): void
    {
        // A handler may flush again: forget what is being dispatched before it does.
        $saved = $this->saved;
        $removed = $this->removed;
        $this->saved = [];
        $this->removed = [];

        foreach ($saved as [$transaction, $changeSet]) {
            if ((new IsNewTransaction($changeSet))->isSatisfiedBy($transaction)) {
                $this->dispatch(new TransactionRecorded((string) $transaction->getId()), $transaction);
                continue;
            }

            $relevant = new HasDetectionRelevantChange($changeSet);
            if ($relevant->isSatisfiedBy($transaction)) {
                $this->dispatch(new TransactionChanged((string) $transaction->getId(), $relevant->changedFields()), $transaction);
            }
        }

        foreach ($removed as $transaction) {
            if ((new IsLinkedTransactionRemoved())->isSatisfiedBy($transaction)) {
                $this->dispatch(
                    new TransactionRemoved((string) $transaction->getId(), (string) $transaction->getCounterpart()->getId()),
                    $transaction,
                );
            }
        }
    }

    private function dispatch(object $event, Transaction $transaction): void
    {
        // The transaction is already stored: a failing effect must not turn its save into an error.
        try {
            $this->eventBus->dispatch($event);
        } catch (\Throwable $e) {
            $this->logger->error('Transaction {transaction}: {event} failed: {error}', [
                'transaction' => (string) $transaction->getId(),
                'event' => $event::class,
                'error' => $e->getMessage(),
                'exception' => $e,
            ]);
        }
    }
}
