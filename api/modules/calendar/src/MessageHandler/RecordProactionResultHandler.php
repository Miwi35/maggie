<?php

declare(strict_types=1);

namespace Maggie\Calendar\MessageHandler;

use Maggie\Calendar\Entity\Proaction;
use Maggie\Calendar\Message\RecordProactionResultCommand;
use Maggie\Calendar\Service\Proactions;
use Maggie\Core\Repository\UserRepository;
use Symfony\Component\Messenger\Attribute\AsMessageHandler;

#[AsMessageHandler]
class RecordProactionResultHandler
{
    public function __construct(
        private readonly Proactions $proactions,
        private readonly UserRepository $userRepository,
    ) {
    }

    public function __invoke(RecordProactionResultCommand $command): Proaction
    {
        $user = null !== $command->userId
            ? ($this->userRepository->find($command->userId) ?? throw new \DomainException('No user found.'))
            : null;

        return $this->proactions->record($user, $command->proactionId, $command->status, $command->response, $command->error);
    }
}
