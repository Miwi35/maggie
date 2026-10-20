<?php

declare(strict_types=1);

namespace Maggie\Finance\MessageHandler;

use Maggie\Calendar\Entity\Event;
use Maggie\Core\Repository\UserRepository;
use Maggie\Finance\Message\EnsureFinanceReviewAppointmentCommand;
use Maggie\Finance\Service\FinanceReviewAppointment;
use Symfony\Component\Messenger\Attribute\AsMessageHandler;

#[AsMessageHandler]
class EnsureFinanceReviewAppointmentHandler
{
    public function __construct(
        private readonly FinanceReviewAppointment $appointment,
        private readonly UserRepository $userRepository,
    ) {
    }

    public function __invoke(EnsureFinanceReviewAppointmentCommand $command): ?Event
    {
        $user = $this->userRepository->find($command->userId)
            ?? throw new \DomainException('User not found.');

        return $this->appointment->lay($user);
    }
}
