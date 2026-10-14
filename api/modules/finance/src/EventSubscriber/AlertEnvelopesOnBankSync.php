<?php

declare(strict_types=1);

namespace Maggie\Finance\EventSubscriber;

use Maggie\Core\Repository\UserRepository;
use Maggie\Finance\Event\BankSyncCompleted;
use Maggie\Finance\UseCase\AlertEnvelopeLimits;
use Symfony\Component\Messenger\Attribute\AsMessageHandler;

/** After a bank sync, Maggie says which envelopes it took past their threshold (MAG-49). */
final class AlertEnvelopesOnBankSync
{
    public function __construct(
        private readonly AlertEnvelopeLimits $alertEnvelopeLimits,
        private readonly UserRepository $userRepository,
    ) {
    }

    #[AsMessageHandler(bus: 'event.bus')]
    public function __invoke(BankSyncCompleted $event): void
    {
        $user = $this->userRepository->find($event->userId);
        if (null === $user || [] === $event->transactionIds) {
            return;
        }

        $this->alertEnvelopeLimits->execute($user, $event->transactionIds);
    }
}
