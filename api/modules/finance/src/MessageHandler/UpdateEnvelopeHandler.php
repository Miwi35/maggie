<?php

declare(strict_types=1);

namespace Maggie\Finance\MessageHandler;

use Maggie\Finance\Entity\Envelope;
use Maggie\Finance\Enum\BudgetMode;
use Maggie\Finance\Message\UpdateEnvelopeCommand;
use Maggie\Finance\Repository\CategoryRepository;
use Maggie\Finance\Repository\EnvelopeRepository;
use Maggie\Finance\UseCase\UpdateEnvelope;
use Symfony\Component\Messenger\Attribute\AsMessageHandler;

#[AsMessageHandler]
class UpdateEnvelopeHandler
{
    public function __construct(
        private readonly UpdateEnvelope $updateEnvelope,
        private readonly EnvelopeRepository $envelopeRepository,
        private readonly CategoryRepository $categoryRepository,
    ) {
    }

    public function __invoke(UpdateEnvelopeCommand $command): Envelope
    {
        $envelope = $this->envelopeRepository->find($command->envelopeId)
            ?? throw new \DomainException("Envelope not found: {$command->envelopeId}");

        if ($command->categoryId !== null) {
            $category = $this->categoryRepository->find($command->categoryId)
                ?? throw new \DomainException("Category not found: {$command->categoryId}");
            $envelope->setCategory($category);
        }
        if ($command->amountCents !== null) {
            $envelope->setAmountCents($command->amountCents);
        }
        if ($command->year !== null) {
            $envelope->setYear($command->year);
        }
        if ($command->mode !== null) {
            $envelope->setMode(BudgetMode::from($command->mode));
        }
        if ($command->month !== null) {
            $envelope->setMonth($command->month);
        }
        if ($command->currency !== null) {
            $envelope->setCurrency($command->currency);
        }

        if ($envelope->getMode() === BudgetMode::Annual) {
            $envelope->setMonth(null);
        } elseif ($envelope->getMonth() === null) {
            throw new \DomainException('A monthly envelope requires a month.');
        }

        return $this->updateEnvelope->execute($envelope);
    }
}
