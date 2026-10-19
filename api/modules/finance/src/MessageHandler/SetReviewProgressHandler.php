<?php

declare(strict_types=1);

namespace Maggie\Finance\MessageHandler;

use Maggie\Core\Repository\UserRepository;
use Maggie\Finance\Entity\ReviewProgress;
use Maggie\Finance\Message\SetReviewProgressCommand;
use Maggie\Finance\UseCase\SetReviewProgress;
use Symfony\Component\Messenger\Attribute\AsMessageHandler;

#[AsMessageHandler]
class SetReviewProgressHandler
{
    public function __construct(
        private readonly SetReviewProgress $setReviewProgress,
        private readonly UserRepository $userRepository,
    ) {
    }

    public function __invoke(SetReviewProgressCommand $command): ReviewProgress
    {
        $user = $this->userRepository->find($command->userId)
            ?? throw new \DomainException('User not found.');

        return $this->setReviewProgress->execute($user, $command->month, $command->step, $command->handledTransactionIds);
    }
}
