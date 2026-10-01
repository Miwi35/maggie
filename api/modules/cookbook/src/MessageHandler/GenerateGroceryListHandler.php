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

        $timeZone = new \DateTimeZone('Europe/Paris');
        $from = $this->day($command->fromDate, $timeZone)->setTime(0, 0);
        // The whole of the last day: meals are stored at midnight in Paris, so
        // a range ending at midnight keeps or drops the last day depending on
        // the server's own time zone.
        $to = $this->day($command->toDate, $timeZone)->setTime(23, 59, 59);

        // Returned on purpose: the Mercure and Elasticsearch middlewares read
        // the handler's result, so this is what gets published and reindexed.
        return $this->groceryGenerationService->generate($user, $from, $to);
    }

    /**
     * Accepts what the model actually sends: a bare day, but also a full
     * timestamp. Appending a time to the string would throw on the latter.
     */
    private function day(string $date, \DateTimeZone $timeZone): \DateTimeImmutable
    {
        try {
            return new \DateTimeImmutable($date, $timeZone);
        } catch (\Exception $e) {
            throw new \DomainException("Not a date: {$date}. Use YYYY-MM-DD.", 0, $e);
        }
    }
}
