<?php

declare(strict_types=1);

namespace Maggie\Finance\MessageHandler;

use Maggie\Finance\Message\SplitEnvelopeCommand;
use Maggie\Finance\Repository\EnvelopeRepository;
use Maggie\Finance\UseCase\SplitEnvelope;
use Symfony\Component\Messenger\Attribute\AsMessageHandler;

#[AsMessageHandler]
class SplitEnvelopeHandler
{
    public function __construct(
        private readonly SplitEnvelope $splitEnvelope,
        private readonly EnvelopeRepository $envelopeRepository,
    ) {
    }

    public function __invoke(SplitEnvelopeCommand $command): void
    {
        $envelope = $this->envelopeRepository->findOneBy(['id' => $command->envelopeId, 'user' => $command->userId])
            ?? throw new \DomainException("Envelope not found: {$command->envelopeId}");

        $this->splitEnvelope->execute($envelope, $command->amountsByEnvelopeId);
    }
}
