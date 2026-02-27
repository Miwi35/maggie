<?php

declare(strict_types=1);

namespace Maggie\Cookbook\Mcp\Tool;

use Maggie\Cookbook\Service\CiqualClient;
use Mcp\Capability\Attribute\McpTool;

#[McpTool(name: 'search_ciqual_foods', description: 'Search the Ciqual (ANSES) French food composition database by name. Returns foods with core macros (kcal, protein, carbs, fat per 100g). Use this to find nutrition data for ingredients.')]
class SearchCiqualFoodsTool
{
    public function __construct(
        private readonly CiqualClient $ciqualClient,
    ) {
    }

    public function __invoke(string $query, int $limit = 10): string
    {
        $foods = $this->ciqualClient->searchFoods($query, $limit);

        $results = [];
        foreach ($foods as $food) {
            $results[] = [
                'alimCode' => $food['alim_code'],
                'alimNameFr' => $food['alim_name_fr'],
                'alimGroupNameFr' => $food['alim_group_name_fr'] ?? null,
                'alimSsgroupNameFr' => $food['alim_ssgroup_name_fr'] ?? null,
                'kcalPer100g' => $food['kcal_per100g'] ?? null,
                'proteinPer100g' => $food['protein_per100g'] ?? null,
                'carbsPer100g' => $food['carbs_per100g'] ?? null,
                'fatPer100g' => $food['fat_per100g'] ?? null,
            ];
        }

        return json_encode(['foods' => $results], JSON_THROW_ON_ERROR);
    }
}
