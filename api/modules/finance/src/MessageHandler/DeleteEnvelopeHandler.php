<?php

declare(strict_types=1);

namespace Maggie\Finance\MessageHandler;

use Maggie\Finance\Message\DeleteEnvelopeCommand;
use Maggie\Finance\Repository\EnvelopeRepository;
use Maggie\Finance\UseCase\DeleteEnvelope;
use Symfony\Component\Messenger\Attribute\AsMessageHandler;

#[AsMessageHandler]
class DeleteEnvelopeHandler
{
    public function __construct(
        private readonly DeleteEnvelope $deleteEnvelope,
        private readonly EnvelopeRepository $envelopeRepository,
    ) {
    }

    public function __invoke(DeleteEnvelopeCommand $command): void
    {
        $envelope = $this->envelopeRepository->findOneBy(['id' => $command->envelopeId, 'user' => $command->userId])
            ?? throw new \DomainException("Envelope not found: {$command->envelopeId}");

        $this->deleteEnvelope->execute($envelope);
    }
}
