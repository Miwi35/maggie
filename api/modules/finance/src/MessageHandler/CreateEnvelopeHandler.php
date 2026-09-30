<?php

declare(strict_types=1);

namespace Maggie\Finance\MessageHandler;

use Maggie\Core\Repository\UserRepository;
use Maggie\Finance\Entity\Envelope;
use Maggie\Finance\Enum\BudgetMode;
use Maggie\Finance\Message\CreateEnvelopeCommand;
use Maggie\Finance\Repository\CategoryRepository;
use Maggie\Finance\Repository\EnvelopeRepository;
use Maggie\Finance\UseCase\CreateEnvelope;
use Symfony\Component\Messenger\Attribute\AsMessageHandler;

#[AsMessageHandler]
class CreateEnvelopeHandler
{
    public function __construct(
        private readonly CreateEnvelope $createEnvelope,
        private readonly EnvelopeRepository $envelopeRepository,
        private readonly CategoryRepository $categoryRepository,
        private readonly UserRepository $userRepository,
    ) {
    }

    public function __invoke(CreateEnvelopeCommand $command): Envelope
    {
        $user = $this->userRepository->find($command->userId)
            ?? throw new \DomainException('User not found.');

        $category = $this->categoryRepository->find($command->categoryId)
            ?? throw new \DomainException("Category not found: {$command->categoryId}");

        $mode = BudgetMode::from($command->mode);
        $month = BudgetMode::Monthly === $mode ? $command->month : null;

        if (BudgetMode::Monthly === $mode && null === $month) {
            throw new \DomainException('A monthly envelope requires a month.');
        }

        if (null !== $this->envelopeRepository->findOneForPeriod($category, $mode, $command->year, $month)) {
            throw new \DomainException('An envelope already budgets this category for that period.');
        }

        $envelope = new Envelope();
        $envelope->setUser($user);
        $envelope->setCategory($category);
        $envelope->setMode($mode);
        $envelope->setAmountCents($command->amountCents);
        $envelope->setYear($command->year);
        $envelope->setMonth($month);
        $envelope->setCurrency($command->currency);

        return $this->createEnvelope->execute($envelope);
    }
}
