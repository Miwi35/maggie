<?php

declare(strict_types=1);

namespace Maggie\Cookbook\Mcp\Tool;

use Maggie\Cookbook\Repository\MealRepository;
use Mcp\Capability\Attribute\McpTool;

#[McpTool(name: 'get_meals', description: 'Get meals for a date range. Date format: YYYY-MM-DD. Returns meals with slot and recipe names.')]
class GetMealsTool
{
    public function __construct(
        private readonly MealRepository $mealRepository,
    ) {
    }

    public function __invoke(
        string $fromDate,
        string $toDate,
    ): string {
        $from = new \DateTimeImmutable($fromDate, new \DateTimeZone('Europe/Paris'));
        $to = new \DateTimeImmutable($toDate . ' 23:59:59', new \DateTimeZone('Europe/Paris'));

        $meals = $this->mealRepository->findByDateRange($from, $to);

        $results = array_map(fn ($m) => [
            'id' => (string) $m->getId(),
            'date' => $m->getStartAt()->format('Y-m-d'),
            'slot' => $m->getSlot()->value,
            'summary' => $m->getSummary(),
            'recipes' => $m->getRecipes()->map(fn ($r) => [
                'id' => (string) $r->getId(),
                'name' => $r->getName(),
            ])->toArray(),
        ], $meals);

        return json_encode(['meals' => $results], JSON_THROW_ON_ERROR);
    }
}
