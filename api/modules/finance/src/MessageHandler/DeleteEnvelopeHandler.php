<?php

declare(strict_types=1);

namespace Maggie\Finance\MessageHandler;

use Maggie\Finance\Message\DeleteEnvelopeCommand;
use Maggie\Finance\Repository\EnvelopeRepository;
use Maggie\Finance\Repository\TransactionRepository;
use Maggie\Finance\UseCase\DeleteEnvelope;
use Symfony\Component\Messenger\Attribute\AsMessageHandler;

#[AsMessageHandler]
class DeleteEnvelopeHandler
{
    public function __construct(
        private readonly DeleteEnvelope $deleteEnvelope,
        private readonly EnvelopeRepository $envelopeRepository,
        private readonly TransactionRepository $transactionRepository,
    ) {
    }

    public function __invoke(DeleteEnvelopeCommand $command): void
    {
        $envelope = $this->envelopeRepository->findOneBy(['id' => $command->envelopeId, 'user' => $command->userId])
            ?? throw new \DomainException("Envelope not found: {$command->envelopeId}");

        // The database would free them too (SET NULL), but unseen: through the
        // entities, each freed spend is published and reindexed as well.
        foreach ($this->transactionRepository->findByOneOffEnvelope($envelope) as $transaction) {
            $transaction->setOneOffEnvelope(null);
        }

        $this->deleteEnvelope->execute($envelope);
    }
}
