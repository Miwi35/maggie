<?php

declare(strict_types=1);

namespace Maggie\Finance\MessageHandler;

use Maggie\Finance\Entity\Envelope;
use Maggie\Finance\Enum\BudgetMode;
use Maggie\Finance\Message\UpdateEnvelopeCommand;
use Maggie\Finance\Repository\EnvelopeRepository;
use Maggie\Finance\Service\OwnedReferenceResolver;
use Maggie\Finance\UseCase\UpdateEnvelope;
use Symfony\Component\Messenger\Attribute\AsMessageHandler;

#[AsMessageHandler]
class UpdateEnvelopeHandler
{
    public function __construct(
        private readonly UpdateEnvelope $updateEnvelope,
        private readonly EnvelopeRepository $envelopeRepository,
        private readonly OwnedReferenceResolver $references,
    ) {
    }

    public function __invoke(UpdateEnvelopeCommand $command): Envelope
    {
        $envelope = $this->envelopeRepository->findOneBy(['id' => $command->envelopeId, 'user' => $command->userId])
            ?? throw new \DomainException("Envelope not found: {$command->envelopeId}");

        if (null !== $command->categoryId) {
            $category = $this->references->category($command->categoryId, $envelope->getUser());
            $envelope->setCategory($category);
        }
        if (null !== $command->amountCents) {
            $envelope->setAmountCents($command->amountCents);
        }
        if (null !== $command->year) {
            $envelope->setYear($command->year);
        }
        if (null !== $command->mode) {
            $envelope->setMode(BudgetMode::from($command->mode));
        }
        if (null !== $command->month) {
            $envelope->setMonth($command->month);
        }
        if (null !== $command->currency) {
            $envelope->setCurrency($command->currency);
        }

        if (BudgetMode::Annual === $envelope->getMode()) {
            $envelope->setMonth(null);
        } elseif (null === $envelope->getMonth()) {
            throw new \DomainException('A monthly envelope requires a month.');
        }

        return $this->updateEnvelope->execute($envelope);
    }
}
