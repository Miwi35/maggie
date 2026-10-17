<?php

declare(strict_types=1);

namespace Maggie\Finance\Doctrine;

use Doctrine\Bundle\DoctrineBundle\Attribute\AsDoctrineListener;
use Doctrine\ORM\Event\OnFlushEventArgs;
use Doctrine\ORM\Events;
use Maggie\Core\Entity\User;
use Maggie\Core\Projection\WorkCollector;
use Maggie\Finance\Entity\PeerDebt;
use Maggie\Finance\Entity\PeerDebtShare;
use Maggie\Finance\Enum\PeerDebtDirection;
use Maggie\Finance\Repository\SafetyCushionRepository;

/**
 * The cushion is what its accounts hold less what is lent to people and not
 * given back (MAG-454): a créance that is opened, repaid, edited or removed
 * moves it, so the cushion is published again for the screens that show it.
 *
 * Only the créances count — a dette never changes the cushion. The status is
 * computed on read, so the update is the signal to read it again.
 */
#[AsDoctrineListener(event: Events::onFlush)]
#[AsDoctrineListener(event: Events::postFlush)]
final class RepublishCushionOnPeerClaimListener
{
    /** @var array<string, User> the owners whose créances this flush touches, by user id */
    private array $owners = [];

    public function __construct(
        private readonly SafetyCushionRepository $cushions,
        private readonly WorkCollector $collector,
    ) {
    }

    public function onFlush(OnFlushEventArgs $args): void
    {
        $uow = $args->getObjectManager()->getUnitOfWork();

        foreach ([
            ...$uow->getScheduledEntityInsertions(),
            ...$uow->getScheduledEntityUpdates(),
            ...$uow->getScheduledEntityDeletions(),
        ] as $entity) {
            $debt = $entity instanceof PeerDebtShare ? $entity->getPeerDebt() : $entity;
            if ($debt instanceof PeerDebt && PeerDebtDirection::OwedToMe === $debt->getDirection()) {
                $owner = $debt->getUser();
                $this->owners[(string) $owner->getId()] = $owner;
            }
        }
    }

    public function postFlush(): void
    {
        $owners = $this->owners;
        $this->owners = [];

        foreach ($owners as $owner) {
            // A user who never opened the cushion has nothing on screen to redraw.
            $cushion = $this->cushions->findOneByUser($owner);
            if (null !== $cushion) {
                $this->collector->touched($cushion, ['peerClaims']);
            }
        }
    }
}
