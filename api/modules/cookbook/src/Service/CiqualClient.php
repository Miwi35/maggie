<?php

declare(strict_types=1);

namespace Maggie\Cookbook\Service;

use Symfony\Contracts\HttpClient\HttpClientInterface;

class CiqualClient
{
    public function __construct(
        private readonly HttpClientInterface $httpClient,
        private readonly string $ciqualServiceUrl = 'http://ciqual:8002',
    ) {
    }

    /** @return list<array{alim_code: string, alim_name_fr: string, alim_group_code: ?string, alim_group_name_fr: ?string, alim_ssgroup_code: ?string, alim_ssgroup_name_fr: ?string, kcal_per100g: ?float, protein_per100g: ?float, carbs_per100g: ?float, fat_per100g: ?float}> */
    public function searchFoods(string $query, int $limit = 10): array
    {
        $response = $this->httpClient->request('GET', $this->ciqualServiceUrl . '/ciqual/foods', [
            'query' => [
                'q' => $query,
                'limit' => $limit,
            ],
        ]);

        return $response->toArray();
    }

    /** @return ?array{alim_code: string, alim_name_fr: string, alim_group_code: ?string, alim_group_name_fr: ?string, alim_ssgroup_code: ?string, alim_ssgroup_name_fr: ?string, nutrients: list<array{const_code: string, const_name_fr: string, const_unit: ?string, value: ?float, confidence_code: ?string, raw_value: ?string}>} */
    public function getFood(string $alimCode): ?array
    {
        $response = $this->httpClient->request('GET', $this->ciqualServiceUrl . '/ciqual/foods/' . $alimCode);

        if ($response->getStatusCode() === 404) {
            return null;
        }

        return $response->toArray();
    }
}
