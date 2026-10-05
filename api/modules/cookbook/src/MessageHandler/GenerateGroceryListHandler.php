<?php

declare(strict_types=1);

namespace Maggie\Cookbook\MessageHandler;

use Maggie\Cookbook\Message\GenerateGroceryListCommand;
use Maggie\Cookbook\Service\GroceryGenerationService;
use Maggie\Core\Repository\UserRepository;
use Maggie\Grocery\Entity\GroceryList;
use Symfony\Component\Messenger\Attribute\AsMessageHandler;

#[AsMessageHandler]
class GenerateGroceryListHandler
{
    public function __construct(
        private readonly GroceryGenerationService $groceryGenerationService,
        private readonly UserRepository $userRepository,
    ) {
    }

    public function __invoke(GenerateGroceryListCommand $command): GroceryList
    {
        $user = $this->userRepository->find($command->userId)
            ?? throw new \DomainException('User not found.');

        // A range of days, both ends included: a meal is a day (MAG-251), so
        // there is no end-of-day instant to reach for.
        return $this->groceryGenerationService->generate(
            $user,
            $this->day($command->fromDate),
            $this->day($command->toDate),
        );
    }

    /**
     * Accepts what the model actually sends: a bare day, but also a full
     * timestamp — of which only the day is kept. Read in Paris, where the
     * owner plans his week: a timestamp just past midnight there is still the
     * day before in UTC.
     */
    private function day(string $date): \DateTimeImmutable
    {
        try {
            $read = new \DateTimeImmutable($date, new \DateTimeZone('Europe/Paris'));
        } catch (\Exception $e) {
            throw new \DomainException("Not a date: {$date}. Use YYYY-MM-DD.", 0, $e);
        }

        return new \DateTimeImmutable($read->format('Y-m-d'), new \DateTimeZone('UTC'));
    }
}
