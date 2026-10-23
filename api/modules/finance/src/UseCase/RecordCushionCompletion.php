<?php

declare(strict_types=1);

namespace Maggie\Finance\UseCase;

use Doctrine\ORM\EntityManagerInterface;
use Maggie\Core\Entity\User;
use Maggie\Finance\Enum\CushionState;

/**
 * Remembers the first time the safety net was full: that is what tells
 * « still building » from « was complete, now recharging ».
 *
 * A write, so it is not part of reading the status ({@see GetCushionStatus}
 * only reports, and says « complete » whether or not this ran). It runs when
 * the figures are rebuilt, which every change of the cushion's accounts
 * triggers.
 */
class RecordCushionCompletion
{
    public function __construct(
        private readonly GetCushionStatus $getCushionStatus,
        private readonly EnsureSafetyCushion $ensureSafetyCushion,
        private readonly EntityManagerInterface $em,
    ) {
    }

    public function execute(User $user): void
    {
        if (CushionState::Complete->value !== $this->getCushionStatus->execute($user)['state']) {
            return;
        }

        $cushion = $this->ensureSafetyCushion->execute($user);
        if (null === $cushion->getCompletedAt()) {
            $cushion->setCompletedAt(new \DateTimeImmutable());
            $this->em->flush();
        }
    }
}
