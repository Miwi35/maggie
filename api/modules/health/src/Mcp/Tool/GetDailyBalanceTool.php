<?php

declare(strict_types=1);

namespace Maggie\Health\Mcp\Tool;

use Maggie\Core\Mcp\McpUserContext;
use Maggie\Core\Mcp\MissingMcpUserException;
use Maggie\Health\Service\DailyBalanceReader;
use Mcp\Capability\Attribute\McpTool;

#[McpTool(name: 'get_daily_balance', description: 'Reads one day\'s calorie balance, date YYYY-MM-DD. Returns {date, eatenKcal, plannedKcal (planned meals not yet eaten), expendedKcal (get_daily_energy), balanceKcal = eaten + planned - expended, proteinG, carbsG, fatG, plannedMeals: [{mealId, slot, label, kcal, proteinG, carbsG, fatG}]}. expendedKcal, balanceKcal are null while the profile is incomplete.')]
final class GetDailyBalanceTool
{
    public function __construct(
        private readonly DailyBalanceReader $reader,
        private readonly McpUserContext $userContext,
    ) {
    }

    public function __invoke(string $date): string
    {
        try {
            return json_encode($this->reader->day($this->userContext->requireUser(), $date), JSON_THROW_ON_ERROR);
        } catch (MissingMcpUserException|\InvalidArgumentException $e) {
            return json_encode(['error' => $e->getMessage()], JSON_THROW_ON_ERROR);
        }
    }
}
