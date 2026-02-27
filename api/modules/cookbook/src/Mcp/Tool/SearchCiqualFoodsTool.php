<?php

declare(strict_types=1);

namespace Maggie\Cookbook\Mcp\Tool;

use Maggie\Cookbook\Entity\CiqualFoodNutrient;
use Maggie\Cookbook\Repository\CiqualFoodRepository;
use Mcp\Capability\Attribute\McpTool;

#[McpTool(name: 'search_ciqual_foods', description: 'Search the Ciqual (ANSES) French food composition database by name. Returns foods with core macros (kcal, protein, carbs, fat per 100g). Use this to find nutrition data for ingredients.')]
class SearchCiqualFoodsTool
{
    private const KCAL_CODE = '328';
    private const PROTEIN_CODE = '25000';
    private const CARBS_CODE = '31000';
    private const FAT_CODE = '40000';

    public function __construct(
        private readonly CiqualFoodRepository $ciqualFoodRepository,
    ) {
    }

    public function __invoke(string $query, int $limit = 10): string
    {
        $foods = $this->ciqualFoodRepository->searchByName($query);
        $foods = \array_slice($foods, 0, $limit);

        $results = [];
        foreach ($foods as $food) {
            $macros = $this->extractMacros($food->getNutrients()->toArray());

            $results[] = [
                'id' => (string) $food->getId(),
                'alimCode' => $food->getAlimCode(),
                'alimNameFr' => $food->getAlimNameFr(),
                'alimGroupNameFr' => $food->getAlimGroupNameFr(),
                'alimSsgroupNameFr' => $food->getAlimSsgroupNameFr(),
                'kcalPer100g' => $macros['kcal'],
                'proteinPer100g' => $macros['protein'],
                'carbsPer100g' => $macros['carbs'],
                'fatPer100g' => $macros['fat'],
            ];
        }

        return json_encode(['foods' => $results], JSON_THROW_ON_ERROR);
    }

    /** @param CiqualFoodNutrient[] $nutrients */
    private function extractMacros(array $nutrients): array
    {
        $macros = ['kcal' => null, 'protein' => null, 'carbs' => null, 'fat' => null];

        foreach ($nutrients as $fn) {
            $code = $fn->getNutrient()->getConstCode();
            match ($code) {
                self::KCAL_CODE => $macros['kcal'] = $fn->getValue(),
                self::PROTEIN_CODE => $macros['protein'] = $fn->getValue(),
                self::CARBS_CODE => $macros['carbs'] = $fn->getValue(),
                self::FAT_CODE => $macros['fat'] = $fn->getValue(),
                default => null,
            };
        }

        return $macros;
    }
}
