<?php

declare(strict_types=1);

namespace Maggie\Core\Doctrine;

use Doctrine\Bundle\DoctrineBundle\Attribute\AsDoctrineListener;
use Doctrine\ORM\Event\PostLoadEventArgs;
use Doctrine\ORM\Event\PreFlushEventArgs;
use Doctrine\ORM\Events;
use Maggie\Core\Contract\HoldsConnectedServiceTokens;
use Maggie\Core\Entity\User;
use Maggie\Core\Security\TokenCipher;
use Maggie\Core\Security\TokenCipherUnavailable;
use Maggie\Core\Security\TokenUnreadable;
use Psr\Log\LoggerInterface;

/**
 * Seals connected-service tokens at flush and opens them at load (MAG-889):
 * the columns only ever hold sealed values, callers only ever see plain ones.
 *
 * preFlush runs before the changesets are computed, so the sealed column it
 * stores is what the flush writes.
 */
#[AsDoctrineListener(event: Events::preFlush)]
#[AsDoctrineListener(event: Events::postLoad)]
final class ConnectedServiceTokensListener
{
    public function __construct(
        private readonly TokenCipher $cipher,
        private readonly LoggerInterface $logger,
    ) {
    }

    public function preFlush(PreFlushEventArgs $args): void
    {
        $uow = $args->getObjectManager()->getUnitOfWork();

        $holders = [];
        foreach ($uow->getIdentityMap() as $class => $entities) {
            if (!is_a($class, HoldsConnectedServiceTokens::class, true)) {
                continue;
            }
            foreach ($entities as $entity) {
                $holders[spl_object_id($entity)] = $entity;
            }
        }
        foreach ($uow->getScheduledEntityInsertions() as $entity) {
            if ($entity instanceof HoldsConnectedServiceTokens) {
                $holders[spl_object_id($entity)] = $entity;
            }
        }

        foreach ($holders as $entity) {
            \assert($entity instanceof HoldsConnectedServiceTokens);
            foreach ($entity->tokensToSeal() as $usage => $plain) {
                if (null === $plain) {
                    $entity->storeSealedToken($usage, null);
                    continue;
                }

                try {
                    $entity->storeSealedToken($usage, $this->cipher->seal($plain, $entity->tokenContext($usage)));
                } catch (TokenCipherUnavailable $e) {
                    // Signing in saves the user with its Google tokens: a missing key must never lock
                    // the owner out of the app. The token stays in memory, unsaved, never in plain.
                    if (!$entity instanceof User) {
                        throw $e;
                    }
                    $this->logger->critical('Connected-service token {usage} of {record} was not saved: {reason}', [
                        'usage' => $usage,
                        'record' => $entity->tokenContext($usage),
                        'reason' => $e->getMessage(),
                    ]);
                }
            }
        }
    }

    public function postLoad(PostLoadEventArgs $args): void
    {
        $entity = $args->getObject();
        if (!$entity instanceof HoldsConnectedServiceTokens) {
            return;
        }

        $plain = [];
        $unreadable = [];
        foreach ($entity->sealedTokens() as $usage => $sealed) {
            if (null === $sealed) {
                $plain[$usage] = null;
                continue;
            }

            try {
                $plain[$usage] = $this->cipher->open($sealed, $entity->tokenContext($usage));
            } catch (TokenCipherUnavailable|TokenUnreadable $e) {
                $unreadable[] = $usage;
                $this->logger->error('Connected-service token {usage} of {record} cannot be opened: {reason}', [
                    'usage' => $usage,
                    'record' => $entity->tokenContext($usage),
                    'reason' => $e->getMessage(),
                ]);
            }
        }

        $entity->openTokens($plain, $unreadable);
    }
}
