<?php

declare(strict_types=1);

namespace Maggie\Finance\MessageHandler;

use Maggie\Core\Repository\UserRepository;
use Maggie\Finance\Entity\Envelope;
use Maggie\Finance\Enum\BudgetMode;
use Maggie\Finance\Message\CreateEnvelopeCommand;
use Maggie\Finance\Repository\EnvelopeRepository;
use Maggie\Finance\Service\OwnedReferenceResolver;
use Maggie\Finance\Service\SeasonalAmounts;
use Maggie\Finance\UseCase\CreateEnvelope;
use Symfony\Component\Messenger\Attribute\AsMessageHandler;

#[AsMessageHandler]
class CreateEnvelopeHandler
{
    public function __construct(
        private readonly CreateEnvelope $createEnvelope,
        private readonly EnvelopeRepository $envelopeRepository,
        private readonly OwnedReferenceResolver $references,
        private readonly UserRepository $userRepository,
    ) {
    }

    public function __invoke(CreateEnvelopeCommand $command): Envelope
    {
        $user = $this->userRepository->find($command->userId)
            ?? throw new \DomainException('User not found.');

        $mode = BudgetMode::from($command->mode);

        if ([] !== $command->seasonalAmounts && BudgetMode::Monthly !== $mode) {
            throw new \DomainException('Only a monthly envelope has seasonal amounts.');
        }

        $envelope = new Envelope();
        $envelope->setUser($user);
        $envelope->setMode($mode);
        $envelope->setAmountCents($command->amountCents);
        $envelope->setCurrency($command->currency);
        $envelope->setAlertThresholdPercent($command->alertThresholdPercent);

        if (BudgetMode::OneOff === $mode) {
            if (null === $command->name || '' === trim($command->name)) {
                throw new \DomainException('A one-off envelope requires a name.');
            }
            if (null !== $command->categoryId) {
                throw new \DomainException('A one-off envelope has no category: spends are attributed to it by hand.');
            }

            // An event, not a period: no year nor month whatever was sent.
            $envelope->setName($command->name);
            $envelope->setYear(null);
            $envelope->setMonth(null);

            return $this->createEnvelope->execute($envelope);
        }

        if (null === $command->categoryId) {
            throw new \DomainException('A monthly or annual envelope requires a category.');
        }
        if (null === $command->year) {
            throw new \DomainException('A monthly or annual envelope requires a year.');
        }

        $category = $this->references->category($command->categoryId, $user);
        $month = BudgetMode::Monthly === $mode ? $command->month : null;

        if (BudgetMode::Monthly === $mode && null === $month) {
            throw new \DomainException('A monthly envelope requires a month.');
        }

        if (null !== $this->envelopeRepository->findOneForPeriod($category, $mode, $command->year, $month)) {
            throw new \DomainException('An envelope already budgets this category for that period.');
        }

        $envelope->setCategory($category);
        $envelope->setYear($command->year);
        $envelope->setMonth($month);
        $envelope->setSeasonalAmounts($this->seasons($command->seasonalAmounts));

        return $this->createEnvelope->execute($envelope);
    }

    /** @return list<array{fromMonth: int, toMonth: int, amountCents: int}> */
    private function seasons(mixed $seasons): array
    {
        try {
            return SeasonalAmounts::normalize($seasons);
        } catch (\InvalidArgumentException $e) {
            throw new \DomainException($e->getMessage(), 0, $e);
        }
    }
}
