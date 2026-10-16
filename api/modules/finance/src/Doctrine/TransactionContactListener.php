<?php

declare(strict_types=1);

namespace Maggie\Finance\Doctrine;

use Doctrine\Bundle\DoctrineBundle\Attribute\AsDoctrineListener;
use Doctrine\ORM\Event\OnFlushEventArgs;
use Doctrine\ORM\Events;
use Maggie\Finance\Contact\CounterpartyMatch;
use Maggie\Finance\Entity\ContactCounterparty;
use Maggie\Finance\Entity\Transaction;

/**
 * Attaches every transaction written — by hand, by an import, by a bank sync —
 * to the contact one of its owner's counterparties recognises, and detaches it
 * when its label or its kind no longer allows it (MAG-450).
 *
 * In onFlush, ahead of the projection and of the transaction events, so the
 * Mercure update and the search document of the line carry its contact.
 */
#[AsDoctrineListener(event: Events::onFlush, priority: 100)]
final class TransactionContactListener
{
    /** Only these columns can change which contact a line belongs to. */
    private const RELEVANT = ['label', 'counterpartyKey', 'transferKind'];

    public function onFlush(OnFlushEventArgs $args): void
    {
        $em = $args->getObjectManager();
        $uow = $em->getUnitOfWork();

        $candidates = [];
        foreach ($uow->getScheduledEntityInsertions() as $entity) {
            if ($entity instanceof Transaction) {
                $candidates[] = $entity;
            }
        }
        foreach ($uow->getScheduledEntityUpdates() as $entity) {
            if ($entity instanceof Transaction && [] !== array_intersect(self::RELEVANT, array_keys($uow->getEntityChangeSet($entity)))) {
                $candidates[] = $entity;
            }
        }
        if ([] === $candidates) {
            return;
        }

        // The counterparties written in this very flush count too: an import
        // and a link saved together must agree.
        $inserted = array_filter($uow->getScheduledEntityInsertions(), static fn (object $e): bool => $e instanceof ContactCounterparty);
        $deleted = array_filter($uow->getScheduledEntityDeletions(), static fn (object $e): bool => $e instanceof ContactCounterparty);

        /** @var array<string, list<ContactCounterparty>> $byUser */
        $byUser = [];
        $meta = $em->getClassMetadata(Transaction::class);
        foreach ($candidates as $transaction) {
            $user = $transaction->getUser();
            $userId = (string) $user->getId();
            $byUser[$userId] ??= array_values(array_filter(
                [
                    ...$em->getRepository(ContactCounterparty::class)->findBy(['user' => $user]),
                    ...array_filter($inserted, static fn (ContactCounterparty $c): bool => $c->getUser()->getId()->equals($user->getId())),
                ],
                static fn (ContactCounterparty $c): bool => !\in_array($c, $deleted, true),
            ));

            $contact = CounterpartyMatch::contactFor($transaction, $byUser[$userId]);
            if ($contact === $transaction->getContact()) {
                continue;
            }

            $transaction->setContact($contact);
            $uow->recomputeSingleEntityChangeSet($meta, $transaction);
        }
    }
}
