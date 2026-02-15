<?php

declare(strict_types=1);

namespace Maggie\Proaction\MessageHandler;

use Maggie\Proaction\Entity\Proaction;
use Maggie\Proaction\Entity\ProactionStatus;
use Maggie\Proaction\Message\UpdateProactionCommand;
use Maggie\Proaction\Repository\ProactionRepository;
use Maggie\Proaction\UseCase\UpdateProaction;
use Symfony\Component\Messenger\Attribute\AsMessageHandler;

#[AsMessageHandler]
class UpdateProactionHandler
{
    public function __construct(
        private readonly UpdateProaction $updateProaction,
        private readonly ProactionRepository $proactionRepository,
    ) {
    }

    public function __invoke(UpdateProactionCommand $command): Proaction
    {
        $proaction = $this->proactionRepository->find($command->proactionId);

        if ($proaction === null) {
            throw new \DomainException("Proaction not found: {$command->proactionId}");
        }

        if ($command->status !== null) {
            $proaction->setStatus(ProactionStatus::from($command->status));
        }
        if ($command->response !== null) {
            $proaction->setResponse($command->response);
        }
        if ($command->error !== null) {
            $proaction->setError($command->error);
        }
        if ($command->completedAt !== null) {
            $proaction->setCompletedAt($command->completedAt);
        }

        return $this->updateProaction->execute($proaction);
    }
}
