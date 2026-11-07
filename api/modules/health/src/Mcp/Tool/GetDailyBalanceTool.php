<?php

declare(strict_types=1);

namespace Maggie\Health\Mcp\Tool;

use Maggie\Core\Mcp\McpUserContext;
use Maggie\Core\Mcp\MissingMcpUserException;
use Maggie\Health\Service\DailyBalanceReader;
use Mcp\Capability\Attribute\McpTool;

#[McpTool(name: 'get_daily_balance', description: 'Read the user\'s calorie balance of one day (Santé › Nutrition), date YYYY-MM-DD. Returns {date, eatenKcal, plannedKcal, expendedKcal, balanceKcal, proteinG, carbsG, fatG, plannedMeals}: eatenKcal is the food journal of the day; plannedKcal the day\'s planned meals not eaten yet (one serving of each recipe); expendedKcal the consolidated expenditure of get_daily_energy (null while the health profile is incomplete); balanceKcal = eatenKcal + plannedKcal - expendedKcal (null with it), positive when more is taken in than spent. Within ±150 kcal the day is balanced: say so plainly, never as a verdict. The macros, in grams, add the eaten and the planned. plannedMeals lists the day\'s planned meals not eaten yet, each {mealId, slot, label, kcal, proteinG, carbsG, fatG} (kcal null: its recipes have no figures, it plans nothing).')]
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
