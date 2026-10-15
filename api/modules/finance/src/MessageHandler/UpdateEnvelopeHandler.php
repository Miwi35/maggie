<?php

declare(strict_types=1);

namespace Maggie\Finance\MessageHandler;

use Maggie\Finance\Entity\Envelope;
use Maggie\Finance\Enum\BudgetMode;
use Maggie\Finance\Message\UpdateEnvelopeCommand;
use Maggie\Finance\Repository\EnvelopeRepository;
use Maggie\Finance\Service\OwnedReferenceResolver;
use Maggie\Finance\Service\SeasonalAmounts;
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

        if (null !== $command->mode && (BudgetMode::OneOff === BudgetMode::from($command->mode)) !== $envelope->isOneOff()) {
            // A budget of a category and an event are not the same thing: one is deleted and the other created.
            throw new \DomainException('An envelope cannot change between one-off and monthly or annual: create a new one.');
        }

        if ($envelope->isOneOff()) {
            if (null !== $command->categoryId || null !== $command->year || null !== $command->month) {
                throw new \DomainException('A one-off envelope has neither category nor period.');
            }
            if (null !== $command->name) {
                if ('' === trim($command->name)) {
                    throw new \DomainException('A one-off envelope requires a name.');
                }
                $envelope->setName($command->name);
            }
        } elseif (null !== $command->name) {
            throw new \DomainException('Only a one-off envelope has a name: the others are named by their category.');
        }

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
        if ($command->setsAlertThreshold) {
            $envelope->setAlertThresholdPercent($command->alertThresholdPercent);
        }
        if (null !== $command->seasonalAmounts) {
            try {
                $envelope->setSeasonalAmounts(SeasonalAmounts::normalize($command->seasonalAmounts));
            } catch (\InvalidArgumentException $e) {
                throw new \DomainException($e->getMessage(), 0, $e);
            }
        }
        if ([] !== $envelope->getSeasonalAmounts() && BudgetMode::Monthly !== $envelope->getMode()) {
            throw new \DomainException('Only a monthly envelope has seasonal amounts.');
        }

        if (BudgetMode::Annual === $envelope->getMode()) {
            $envelope->setMonth(null);
        } elseif (BudgetMode::Monthly === $envelope->getMode() && null === $envelope->getMonth()) {
            throw new \DomainException('A monthly envelope requires a month.');
        }

        return $this->updateEnvelope->execute($envelope);
    }
}
